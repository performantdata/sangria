package sangria.schema

import language.{higherKinds, implicitConversions}
import sangria.execution._
import sangria.marshalling._
import sangria.parser.SourceMapper
import sangria.{ast, introspection}
import sangria.execution.deferred.Deferred
import sangria.streaming.SubscriptionStream
import sangria.util.Cache

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

sealed trait Action[+Ctx, +Val] {
  def map[NewVal](fn: Val => NewVal)(implicit ec: ExecutionContext): Action[Ctx, NewVal]
}
sealed trait LeafAction[+Ctx, +Val] extends Action[Ctx, Val] {
  def map[NewVal](fn: Val => NewVal)(implicit ec: ExecutionContext): LeafAction[Ctx, NewVal]
}
sealed trait ReduceAction[+Ctx, +Val] extends Action[Ctx, Val] {
  def map[NewVal](fn: Val => NewVal)(implicit ec: ExecutionContext): LeafAction[Ctx, NewVal]
}

object ReduceAction {
  implicit def futureAction[Ctx, Val](value: Future[Val]): ReduceAction[Ctx, Val] = FutureValue(
    value)
  implicit def tryAction[Ctx, Val](value: Try[Val]): ReduceAction[Ctx, Val] = TryValue(value)
  implicit def defaultAction[Ctx, Val](value: Val): ReduceAction[Ctx, Val] = Value(value)
}

object Action extends LowPrioActions {
  def sequence[Ctx, Val](actions: Seq[LeafAction[Ctx, Val]]): SequenceLeafAction[Ctx, Val] =
    SequenceLeafAction[Ctx, Val](actions)

  def apply[Ctx, Val](a: Action[Ctx, Val]): Action[Ctx, Val] = a

  implicit def deferredAction[Ctx, Val](value: Deferred[Val]): LeafAction[Ctx, Val] = DeferredValue(
    value)
  implicit def tryAction[Ctx, Val](value: Try[Val]): LeafAction[Ctx, Val] = TryValue(value)
}

trait LowPrioActions extends LowestPrioActions {
  implicit def deferredFutureAction[Ctx, Val](value: Future[Deferred[Val]]): LeafAction[Ctx, Val] =
    DeferredFutureValue(value)
}

trait LowestPrioActions {
  implicit def futureAction[Ctx, Val](value: Future[Val]): LeafAction[Ctx, Val] = FutureValue(value)
  implicit def defaultAction[Ctx, Val](value: Val): LeafAction[Ctx, Val] = Value(value)
}

object LeafAction {
  def sequence[Ctx, Val](actions: Seq[LeafAction[Ctx, Val]]): SequenceLeafAction[Ctx, Val] =
    SequenceLeafAction[Ctx, Val](actions)

  def apply[Ctx, Val](a: LeafAction[Ctx, Val]): LeafAction[Ctx, Val] = a
}

case class Value[Ctx, Val](value: Val) extends LeafAction[Ctx, Val] with ReduceAction[Ctx, Val] {
  override def map[NewVal](fn: Val => NewVal)(implicit
      ec: ExecutionContext): LeafAction[Ctx, NewVal] =
    try Value(fn(value))
    catch {
      case NonFatal(e) => TryValue(Failure(e))
    }
}

case class TryValue[Ctx, Val](value: Try[Val])
    extends LeafAction[Ctx, Val]
    with ReduceAction[Ctx, Val] {
  override def map[NewVal](fn: Val => NewVal)(implicit
      ec: ExecutionContext): TryValue[Ctx, NewVal] =
    TryValue(value.map(fn))
}

case class PartialValue[Ctx, Val](value: Val, errors: Vector[Throwable])
    extends LeafAction[Ctx, Val] {
  override def map[NewVal](fn: Val => NewVal)(implicit
      ec: ExecutionContext): LeafAction[Ctx, NewVal] =
    try PartialValue(fn(value), errors)
    catch {
      case NonFatal(e) => TryValue(Failure(e))
    }
}

case class FutureValue[Ctx, Val](value: Future[Val])
    extends LeafAction[Ctx, Val]
    with ReduceAction[Ctx, Val] {
  override def map[NewVal](fn: Val => NewVal)(implicit
      ec: ExecutionContext): FutureValue[Ctx, NewVal] =
    FutureValue(value.map(fn))
}

case class PartialFutureValue[Ctx, Val](value: Future[PartialValue[Ctx, Val]])
    extends LeafAction[Ctx, Val] {
  override def map[NewVal](fn: Val => NewVal)(implicit
      ec: ExecutionContext): PartialFutureValue[Ctx, NewVal] =
    PartialFutureValue(value.map(_.map(fn) match {
      case v: PartialValue[Ctx, NewVal] => v
      case TryValue(Failure(e)) => throw e
      case v => throw new IllegalStateException("Unexpected result from `PartialValue.map`: " + v)
    }))
}

case class DeferredValue[Ctx, Val](value: Deferred[Val]) extends LeafAction[Ctx, Val] {
  override def map[NewVal](fn: Val => NewVal)(implicit
      ec: ExecutionContext): DeferredValue[Ctx, NewVal] =
    DeferredValue(MappingDeferred(value, (v: Val) => (fn(v), Vector.empty)))

  def mapWithErrors[NewVal](fn: Val => (NewVal, Vector[Throwable])): DeferredValue[Ctx, NewVal] =
    DeferredValue(MappingDeferred(value, fn))
}

case class DeferredFutureValue[Ctx, Val](value: Future[Deferred[Val]])
    extends LeafAction[Ctx, Val] {
  override def map[NewVal](fn: Val => NewVal)(implicit
      ec: ExecutionContext): DeferredFutureValue[Ctx, NewVal] =
    DeferredFutureValue(value.map(MappingDeferred(_, (v: Val) => (fn(v), Vector.empty))))

  def mapWithErrors[NewVal](fn: Val => (NewVal, Vector[Throwable]))(implicit
      ec: ExecutionContext): DeferredFutureValue[Ctx, NewVal] =
    DeferredFutureValue(value.map(MappingDeferred(_, fn)))
}

case class SequenceLeafAction[Ctx, Val](value: Seq[LeafAction[Ctx, Val]])
    extends LeafAction[Ctx, Seq[Val]] {
  override def map[NewVal](fn: Seq[Val] => NewVal)(implicit
      ec: ExecutionContext): MappedSequenceLeafAction[Ctx, Val, NewVal] =
    new MappedSequenceLeafAction[Ctx, Val, NewVal](this, fn)
}

class MappedSequenceLeafAction[Ctx, Val, NewVal](
    val action: SequenceLeafAction[Ctx, Val],
    val mapFn: Seq[Val] => NewVal)
    extends LeafAction[Ctx, NewVal] {
  override def map[NewNewVal](fn: NewVal => NewNewVal)(implicit
      ec: ExecutionContext): MappedSequenceLeafAction[Ctx, Val, NewNewVal] =
    new MappedSequenceLeafAction[Ctx, Val, NewNewVal](action, v => fn(mapFn(v)))
}

class UpdateCtx[Ctx, Val](val action: LeafAction[Ctx, Val], val nextCtx: Val => Ctx)
    extends Action[Ctx, Val] {
  override def map[NewVal](fn: Val => NewVal)(implicit
      ec: ExecutionContext): MappedUpdateCtx[Ctx, Val, NewVal] =
    new MappedUpdateCtx[Ctx, Val, NewVal](action, nextCtx, fn)
}

class MappedUpdateCtx[Ctx, Val, NewVal](
    val action: LeafAction[Ctx, Val],
    val nextCtx: Val => Ctx,
    val mapFn: Val => NewVal)
    extends Action[Ctx, NewVal] {
  override def map[NewNewVal](fn: NewVal => NewNewVal)(implicit
      ec: ExecutionContext): MappedUpdateCtx[Ctx, Val, NewNewVal] =
    new MappedUpdateCtx[Ctx, Val, NewNewVal](action, nextCtx, v => fn(mapFn(v)))
}

object UpdateCtx {
  def apply[Ctx, Val](action: LeafAction[Ctx, Val])(newCtx: Val => Ctx): UpdateCtx[Ctx, Val] =
    new UpdateCtx(action, newCtx)
}

private[sangria] case class SubscriptionValue[Ctx, Val, S[_]](
    source: Val,
    stream: SubscriptionStream[S])
    extends LeafAction[Ctx, Val] {
  override def map[NewVal](fn: Val => NewVal)(implicit
      ec: ExecutionContext): SubscriptionValue[Ctx, NewVal, S] =
    throw new IllegalStateException(
      "`map` is not supported subscription actions. Action is only intended for internal use.")
}

case class ProjectionName(name: String) extends FieldTag
case object ProjectionExclude extends FieldTag

trait Projector[Ctx, Val, Res] extends (Context[Ctx, Val] => Action[Ctx, Res]) {
  val maxLevel: Int = Integer.MAX_VALUE
  def apply(ctx: Context[Ctx, Val], projected: Vector[ProjectedName]): Action[Ctx, Res]
}

object Projector {
  def apply[Ctx, Val, Res](fn: (Context[Ctx, Val], Vector[ProjectedName]) => Action[Ctx, Res]) =
    new Projector[Ctx, Val, Res] {
      def apply(ctx: Context[Ctx, Val], projected: Vector[ProjectedName]): Action[Ctx, Res] =
        fn(ctx, projected)
      override def apply(ctx: Context[Ctx, Val]) = throw new IllegalStateException(
        "Default apply should not be called on projector!")
    }

  def apply[Ctx, Val, Res](
      levels: Int,
      fn: (Context[Ctx, Val], Vector[ProjectedName]) => Action[Ctx, Res]) =
    new Projector[Ctx, Val, Res] {
      override val maxLevel: Int = levels
      def apply(ctx: Context[Ctx, Val], projected: Vector[ProjectedName]): Action[Ctx, Res] =
        fn(ctx, projected)
      override def apply(ctx: Context[Ctx, Val]) = throw new IllegalStateException(
        "Default apply should not be called on projector!")
    }
}

case class ProjectedName(
    name: String,
    children: Vector[ProjectedName] = Vector.empty,
    args: Args = Args.empty) {
  lazy val asVector: Vector[Vector[String]] = {
    def loop(name: ProjectedName): Vector[Vector[String]] =
      Vector(name.name) +: (name.children.flatMap(loop).map(name.name +: _))

    loop(this)
  }
}

case class MappingDeferred[A, +B](deferred: Deferred[A], mapFn: A => (B, Vector[Throwable]))
    extends Deferred[B]

trait WithArguments {
  def args: Args

  def arg[T](arg: Argument[T]): T = args.arg(arg)
  def arg[T](name: String): T = args.arg(name)

  def argOpt[T](name: String): Option[T] = args.argOpt(name)
  def argOpt[T](arg: Argument[T]): Option[T] = args.argOpt(arg)

  def argDefinedInQuery(name: String): Boolean = args.argDefinedInQuery(name)
  def argDefinedInQuery(arg: Argument[_]): Boolean = args.argDefinedInQuery(arg)

  def withArgs[A1, R](arg1: Argument[A1])(fn: A1 => R): R = args.withArgs(arg1)(fn)
  def withArgs[A1, A2, R](arg1: Argument[A1], arg2: Argument[A2])(fn: (A1, A2) => R): R =
    args.withArgs(arg1, arg2)(fn)
  def withArgs[A1, A2, A3, R](arg1: Argument[A1], arg2: Argument[A2], arg3: Argument[A3])(
      fn: (A1, A2, A3) => R): R = args.withArgs(arg1, arg2, arg3)(fn)
  def withArgs[A1, A2, A3, A4, R](
      arg1: Argument[A1],
      arg2: Argument[A2],
      arg3: Argument[A3],
      arg4: Argument[A4])(fn: (A1, A2, A3, A4) => R): R = args.withArgs(arg1, arg2, arg3, arg4)(fn)
  def withArgs[A1, A2, A3, A4, A5, R](
      arg1: Argument[A1],
      arg2: Argument[A2],
      arg3: Argument[A3],
      arg4: Argument[A4],
      arg5: Argument[A5])(fn: (A1, A2, A3, A4, A5) => R): R =
    args.withArgs(arg1, arg2, arg3, arg4, arg5)(fn)
  def withArgs[A1, A2, A3, A4, A5, A6, R](
      arg1: Argument[A1],
      arg2: Argument[A2],
      arg3: Argument[A3],
      arg4: Argument[A4],
      arg5: Argument[A5],
      arg6: Argument[A6])(fn: (A1, A2, A3, A4, A5, A6) => R): R =
    args.withArgs(arg1, arg2, arg3, arg4, arg5, arg6)(fn)
  def withArgs[A1, A2, A3, A4, A5, A6, A7, R](
      arg1: Argument[A1],
      arg2: Argument[A2],
      arg3: Argument[A3],
      arg4: Argument[A4],
      arg5: Argument[A5],
      arg6: Argument[A6],
      arg7: Argument[A7])(fn: (A1, A2, A3, A4, A5, A6, A7) => R): R =
    args.withArgs(arg1, arg2, arg3, arg4, arg5, arg6, arg7)(fn)
  def withArgs[A1, A2, A3, A4, A5, A6, A7, A8, R](
      arg1: Argument[A1],
      arg2: Argument[A2],
      arg3: Argument[A3],
      arg4: Argument[A4],
      arg5: Argument[A5],
      arg6: Argument[A6],
      arg7: Argument[A7],
      arg8: Argument[A8])(fn: (A1, A2, A3, A4, A5, A6, A7, A8) => R): R =
    args.withArgs(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8)(fn)
}

/** @tparam Ctx
  *   Type of the context object that was passed to Sangria's execution method.
  */
trait WithInputTypeRendering[Ctx] {

  /** The context object that was passed to Sangria's execution method. */
  def ctx: Ctx
  def sourceMapper: Option[SourceMapper]
  def deprecationTracker: DeprecationTracker
  def marshaller: ResultMarshaller

  private lazy val coercionHelper =
    new ValueCoercionHelper[Ctx](sourceMapper, deprecationTracker, Some(ctx))

  def renderInputValueCompact[T](value: (_, ToInput[_, _]), tpe: InputType[T]): Option[String] =
    DefaultValueRenderer.renderInputValueCompact(value, tpe, coercionHelper)
}

case class DefaultValueParser[T](
    schema: Schema[_, _],
    parser: InputParser[T],
    toInput: ToInput[T, _])

object DefaultValueParser {
  def forType[T](schema: Schema[_, _])(implicit
      parser: InputParser[T],
      toInput: ToInput[T, _]): DefaultValueParser[T] =
    DefaultValueParser[T](schema, parser, toInput)
}

object DefaultValueRenderer {
  implicit val marshaller: QueryAstResultMarshaller =
    sangria.marshalling.queryAst.queryAstResultMarshaller

  def renderInputValueCompact[T, Ctx](
      value: (_, ToInput[_, _]),
      tpe: InputType[T],
      coercionHelper: ValueCoercionHelper[Ctx]): Option[String] =
    renderInputValue(value, tpe, coercionHelper).map(marshaller.renderCompact)

  def renderInputValuePretty[T, Ctx](
      value: (_, ToInput[_, _]),
      tpe: InputType[T],
      coercionHelper: ValueCoercionHelper[Ctx]): Option[String] =
    renderInputValue(value, tpe, coercionHelper).map(marshaller.renderPretty)

  def renderInputValue[T, Ctx](
      value: (_, ToInput[_, _]),
      tpe: InputType[T],
      coercionHelper: ValueCoercionHelper[Ctx]): Option[marshaller.Node] = {
    val (v, toInput) = value.asInstanceOf[(Any, ToInput[Any, Any])]
    val (inputValue, iu) = toInput.toInput(v)

    if (!iu.isDefined(inputValue))
      None
    else
      coercionHelper.coerceInputValue(
        tpe,
        Nil,
        inputValue,
        None,
        None,
        CoercedScalaResultMarshaller.default,
        CoercedScalaResultMarshaller.default,
        isArgument = false)(iu) match {
        case Right(Trinary.Defined(coerced)) => Some(renderCoercedInputValue(tpe, coerced))
        case _ => None
      }
  }

  def renderCoercedInputValueCompact[T](value: Any, tpe: InputType[T]): String =
    marshaller.renderCompact(renderCoercedInputValue(tpe, value))

  def renderCoercedInputValuePretty[T](value: Any, tpe: InputType[T]): String =
    marshaller.renderPretty(renderCoercedInputValue(tpe, value))

  def renderCoercedInputValue(t: InputType[_], v: Any): marshaller.Node = t match {
    case _ if v == null => marshaller.nullNode
    case s: ScalarType[Any @unchecked] =>
      Resolver.marshalScalarValue(
        s.coerceOutput(v, marshaller.capabilities),
        marshaller,
        s.name,
        s.scalarInfo)
    case s: ScalarAlias[Any @unchecked, Any @unchecked] =>
      renderCoercedInputValue(s.aliasFor, s.toScalar(v))
    case e: EnumType[Any @unchecked] =>
      Resolver.marshalEnumValue(e.coerceOutput(v), marshaller, e.name)
    case io: InputObjectType[_] =>
      val mapValue = v.asInstanceOf[Map[String, Any]]

      val builder = io.fields.foldLeft(marshaller.emptyMapNode(io.fields.map(_.name))) {
        case (acc, field) if mapValue contains field.name =>
          marshaller.addMapNodeElem(
            acc,
            field.name,
            renderCoercedInputValue(field.fieldType, mapValue(field.name)),
            optional = false)
        case (acc, _) => acc
      }

      marshaller.mapNode(builder)
    case l: ListInputType[_] =>
      val listValue = v.asInstanceOf[Seq[Any]]

      marshaller.mapAndMarshal[Any](listValue, renderCoercedInputValue(l.ofType, _))
    case o: OptionInputType[_] =>
      v match {
        case Some(optVal) => renderCoercedInputValue(o.ofType, optVal)
        case None => marshaller.nullNode
        case other => renderCoercedInputValue(o.ofType, other)
      }
  }
}

/** The context of a field during schema resolution.
  *
  * When a GraphQL request is executed by a Sangria server, each [[Field field]] in the request is
  * resolved to determine the data that should be returned. An instance of this class provides the
  * context for a particular field's resolution.
  *
  * @param value
  *   The object to which the field belongs.
  * @param ctx
  *   The context object that was passed to Sangria's execution method.
  * @tparam Ctx
  *   Type of the context object that was passed to Sangria's execution method.
  * @tparam Val
  *   Type of the object to which the field belongs.
  */
case class Context[Ctx, Val](
    value: Val,
    ctx: Ctx,
    args: Args,
    schema: Schema[Ctx, Val],
    field: Field[Ctx, Val],
    parentType: ObjectType[Ctx, Any],
    marshaller: ResultMarshaller,
    query: ast.Document,
    sourceMapper: Option[SourceMapper],
    deprecationTracker: DeprecationTracker,
    astFields: Vector[ast.Field],
    path: ExecutionPath,
    deferredResolverState: Any,
    middlewareAttachments: Vector[MiddlewareAttachment] = Vector.empty
) extends WithArguments
    with WithInputTypeRendering[Ctx] {
  def isIntrospection: Boolean = introspection.isIntrospection(parentType, field)

  def attachment[T <: MiddlewareAttachment: ClassTag]: Option[T] = {
    val clazz = implicitly[ClassTag[T]].runtimeClass

    middlewareAttachments.collectFirst {
      case a if clazz.isAssignableFrom(a.getClass) => a.asInstanceOf[T]
    }
  }

  def attachments[T <: MiddlewareAttachment: ClassTag]: Vector[T] = {
    val clazz = implicitly[ClassTag[T]].runtimeClass

    middlewareAttachments.collect {
      case a if clazz.isAssignableFrom(a.getClass) => a.asInstanceOf[T]
    }
  }
}

case class Args(
    raw: Map[String, Any],
    argsWithDefault: Set[String],
    optionalArgs: Set[String],
    undefinedArgs: Set[String],
    defaultInfo: Cache[String, Any]) {
  private def getAsOptional[T](name: String): Option[T] =
    raw.get(name).asInstanceOf[Option[Option[T]]].flatten

  private def invariantExplicitlyNull(name: String) =
    throw new IllegalArgumentException(
      s"Optional argument '$name' accessed as a non-optional argument (it has a default value), but query explicitly set argument to `null`.")

  private def invariantNotProvided(name: String) =
    throw new IllegalArgumentException(
      s"Optional argument '$name' accessed as a non-optional argument, but it was not provided in the query and argument does not define a default value.")

  def arg[T](arg: Argument[T]): T =
    if (optionalArgs.contains(arg.name) && argsWithDefault.contains(arg.name) && defaultInfo
        .contains(arg.name))
      getAsOptional[T](arg.name).getOrElse(defaultInfo(arg.name).asInstanceOf[T])
    else if (optionalArgs.contains(arg.name) && argsWithDefault.contains(arg.name))
      getAsOptional[T](arg.name).getOrElse(invariantExplicitlyNull(arg.name))
    else if (optionalArgs.contains(arg.name))
      getAsOptional[Any](arg.name).asInstanceOf[T]
    else
      raw(arg.name).asInstanceOf[T]

  def arg[T](name: String): T =
    if (optionalArgs.contains(name) && argsWithDefault.contains(name) && defaultInfo.contains(name))
      getAsOptional[T](name).getOrElse(defaultInfo(name).asInstanceOf[T])
    else if (optionalArgs.contains(name) && argsWithDefault.contains(name))
      getAsOptional[T](name).getOrElse(invariantExplicitlyNull(name))
    else if (optionalArgs.contains(name))
      getAsOptional[T](name).getOrElse(invariantNotProvided(name))
    else
      raw(name).asInstanceOf[T]

  def argOpt[T](name: String): Option[T] = getAsOptional(name)

  def argOpt[T](arg: Argument[T]): Option[T] =
    if (optionalArgs.contains(arg.name))
      getAsOptional[T](arg.name)
    else
      raw.get(arg.name).asInstanceOf[Option[T]]

  def argDefinedInQuery(name: String): Boolean = !undefinedArgs.contains(name)
  def argDefinedInQuery(arg: Argument[_]): Boolean = argDefinedInQuery(arg.name)

  def withArgs[A1, R](arg1: Argument[A1])(fn: A1 => R): R = fn(arg(arg1))
  def withArgs[A1, A2, R](arg1: Argument[A1], arg2: Argument[A2])(fn: (A1, A2) => R): R =
    fn(arg(arg1), arg(arg2))
  def withArgs[A1, A2, A3, R](arg1: Argument[A1], arg2: Argument[A2], arg3: Argument[A3])(
      fn: (A1, A2, A3) => R): R = fn(arg(arg1), arg(arg2), arg(arg3))
  def withArgs[A1, A2, A3, A4, R](
      arg1: Argument[A1],
      arg2: Argument[A2],
      arg3: Argument[A3],
      arg4: Argument[A4])(fn: (A1, A2, A3, A4) => R): R =
    fn(arg(arg1), arg(arg2), arg(arg3), arg(arg4))
  def withArgs[A1, A2, A3, A4, A5, R](
      arg1: Argument[A1],
      arg2: Argument[A2],
      arg3: Argument[A3],
      arg4: Argument[A4],
      arg5: Argument[A5])(fn: (A1, A2, A3, A4, A5) => R): R =
    fn(arg(arg1), arg(arg2), arg(arg3), arg(arg4), arg(arg5))
  def withArgs[A1, A2, A3, A4, A5, A6, R](
      arg1: Argument[A1],
      arg2: Argument[A2],
      arg3: Argument[A3],
      arg4: Argument[A4],
      arg5: Argument[A5],
      arg6: Argument[A6])(fn: (A1, A2, A3, A4, A5, A6) => R): R =
    fn(arg(arg1), arg(arg2), arg(arg3), arg(arg4), arg(arg5), arg(arg6))
  def withArgs[A1, A2, A3, A4, A5, A6, A7, R](
      arg1: Argument[A1],
      arg2: Argument[A2],
      arg3: Argument[A3],
      arg4: Argument[A4],
      arg5: Argument[A5],
      arg6: Argument[A6],
      arg7: Argument[A7])(fn: (A1, A2, A3, A4, A5, A6, A7) => R): R =
    fn(arg(arg1), arg(arg2), arg(arg3), arg(arg4), arg(arg5), arg(arg6), arg(arg7))
  def withArgs[A1, A2, A3, A4, A5, A6, A7, A8, R](
      arg1: Argument[A1],
      arg2: Argument[A2],
      arg3: Argument[A3],
      arg4: Argument[A4],
      arg5: Argument[A5],
      arg6: Argument[A6],
      arg7: Argument[A7],
      arg8: Argument[A8])(fn: (A1, A2, A3, A4, A5, A6, A7, A8) => R): R =
    fn(arg(arg1), arg(arg2), arg(arg3), arg(arg4), arg(arg5), arg(arg6), arg(arg7), arg(arg8))
}

object Args {
  val empty = new Args(Map.empty, Set.empty, Set.empty, Set.empty, Cache.empty)

  def apply(definitions: List[Argument[_]], values: (String, Any)*): Args =
    apply(definitions, values.toMap)

  def apply(definitions: List[Argument[_]], values: Map[String, Any]): Args =
    apply(definitions, input = ScalaInput.scalaInput(values))

  def apply[In: InputUnmarshaller](
      definitions: List[Argument[_]],
      input: In,
      variables: Option[Map[String, VariableValue]] = None): Args = {
    import sangria.marshalling.queryAst._

    val iu = implicitly[InputUnmarshaller[In]]

    if (!iu.isMapNode(input)) {
      throw new IllegalArgumentException("The input expected to be a map-like data structure")
    } else {
      val argsValues =
        iu.getMapKeys(input).flatMap(key => definitions.find(_.name == key)).map { arg =>
          val astValue = iu
            .getRootMapValue(input, arg.name)
            .flatMap(x => this.convert[In, ast.Value](x, arg.argumentType, variables))

          ast.Argument(name = arg.name, value = astValue.getOrElse(ast.NullValue()))
        }

      ValueCollector
        .getArgumentValues(
          ValueCoercionHelper.default,
          None,
          definitions,
          argsValues.toVector,
          Map.empty,
          ExceptionHandler.empty)
        .get
    }
  }

  def apply(schemaElem: HasArguments, astElem: ast.WithArguments): Args = {
    import sangria.marshalling.queryAst._

    apply(
      schemaElem.arguments,
      ast.ObjectValue(
        astElem.arguments.map(arg => ast.ObjectField(arg.name, arg.value))): ast.Value)
  }

  def apply(
      schemaElem: HasArguments,
      astElem: ast.WithArguments,
      variables: Map[String, VariableValue]): Args = {
    import sangria.marshalling.queryAst._

    apply(
      schemaElem.arguments,
      ast.ObjectValue(
        astElem.arguments.map(arg => ast.ObjectField(arg.name, arg.value))): ast.Value,
      Some(variables)
    )
  }

  private def convert[In: InputUnmarshaller, Out: ResultMarshallerForType](
      value: In,
      tpe: InputType[_],
      variables: Option[Map[String, VariableValue]] = None): Option[Out] = {
    val rm = implicitly[ResultMarshallerForType[Out]]

    ValueCoercionHelper.default.coerceInputValue(
      tpe,
      List("stub"),
      value,
      None,
      variables,
      rm.marshaller,
      rm.marshaller,
      isArgument = false) match {
      case Right(v) => v.toOption.asInstanceOf[Option[Out]]
      case Left(violations) => throw AttributeCoercionError(violations, ExceptionHandler.empty)
    }
  }
}

case class DirectiveContext(selection: ast.WithDirectives, directive: Directive, args: Args)
    extends WithArguments
