package sangria.schema

import sangria.execution._
import sangria.marshalling._
import sangria.parser.SourceMapper

import language.{implicitConversions, existentials}

import sangria.ast

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

sealed trait Action[+Ctx, +Val] {
  def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): Action[Ctx, NewVal]
}
sealed trait LeafAction[+Ctx, +Val] extends Action[Ctx, Val] {
  def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): LeafAction[Ctx, NewVal]
}
sealed trait ReduceAction[+Ctx, +Val] extends Action[Ctx, Val] {
  def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): LeafAction[Ctx, NewVal]
}

object ReduceAction {
  implicit def futureAction[Ctx, Val](value: Future[Val]): ReduceAction[Ctx, Val] = FutureValue(value)
  implicit def tryAction[Ctx, Val](value: Try[Val]): ReduceAction[Ctx, Val] = TryValue(value)
  implicit def defaultAction[Ctx, Val](value: Val): ReduceAction[Ctx, Val] = Value(value)
}

object Action extends LowPrioActions {
  implicit def deferredAction[Ctx, Val](value: Deferred[Val]): LeafAction[Ctx, Val] = DeferredValue(value)

  implicit def futureAction[Ctx, Val](value: Future[Val]): LeafAction[Ctx, Val] = FutureValue(value)
  implicit def tryAction[Ctx, Val](value: Try[Val]): LeafAction[Ctx, Val] = TryValue(value)
  implicit def defaultAction[Ctx, Val](value: Val): LeafAction[Ctx, Val] = Value(value)
}

trait LowPrioActions {
  implicit def deferredFutureAction[Ctx, Val, D <: Deferred[Val]](value: Future[D])(implicit ev: D <:< Deferred[Val]): LeafAction[Ctx, Val] = DeferredFutureValue(value)
}

case class Value[Ctx, Val](value: Val) extends LeafAction[Ctx, Val] with ReduceAction[Ctx, Val] {
  override def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): LeafAction[Ctx, NewVal] =
    try Value(fn(value)) catch {
      case NonFatal(e) ⇒ TryValue(Failure(e))
    }
}

case class TryValue[Ctx, Val](value: Try[Val]) extends LeafAction[Ctx, Val] with ReduceAction[Ctx, Val] {
  override def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): TryValue[Ctx, NewVal] =
    TryValue(value map fn)
}

case class PartialValue[Ctx, Val](value: Val, errors: Vector[Throwable]) extends LeafAction[Ctx, Val] {
  override def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): LeafAction[Ctx, NewVal] =
    try PartialValue(fn(value), errors) catch {
      case NonFatal(e) ⇒ TryValue(Failure(e))
    }
}

case class FutureValue[Ctx, Val](value: Future[Val]) extends LeafAction[Ctx, Val] with ReduceAction[Ctx, Val] {
  override def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): FutureValue[Ctx, NewVal] =
    FutureValue(value map fn)
}

case class PartialFutureValue[Ctx, Val](value: Future[PartialValue[Ctx, Val]]) extends LeafAction[Ctx, Val] {
  override def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): PartialFutureValue[Ctx, NewVal] =
    PartialFutureValue(value map (_.map(fn) match {
      case v: PartialValue[Ctx, NewVal] ⇒ v
      case TryValue(Failure(e)) ⇒ throw e
      case v ⇒ throw new IllegalStateException("Unexpected result from `PartialValue.map`: " + v)
    }))
}

case class DeferredValue[Ctx, Val](value: Deferred[Val]) extends LeafAction[Ctx, Val] {
  override def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): DeferredValue[Ctx, NewVal] =
    DeferredValue(MappingDeferred(value, fn))
}

case class DeferredFutureValue[Ctx, Val](value: Future[Deferred[Val]]) extends LeafAction[Ctx, Val] {
  override def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): DeferredFutureValue[Ctx, NewVal] =
    DeferredFutureValue(value map (MappingDeferred(_, fn)))
}

class UpdateCtx[Ctx, Val](val action: LeafAction[Ctx, Val], val nextCtx: Val ⇒ Ctx) extends Action[Ctx, Val] {
  override def map[NewVal](fn: Val ⇒ NewVal)(implicit ec: ExecutionContext): MappedUpdateCtx[Ctx, Val, NewVal] =
    new MappedUpdateCtx[Ctx, Val, NewVal](action, nextCtx, fn)
}

class MappedUpdateCtx[Ctx, Val, NewVal](val action: LeafAction[Ctx, Val], val nextCtx: Val ⇒ Ctx, val mapFn: Val ⇒ NewVal) extends Action[Ctx, NewVal] {
  override def map[NewNewVal](fn: NewVal ⇒ NewNewVal)(implicit ec: ExecutionContext): MappedUpdateCtx[Ctx, Val, NewNewVal] =
    new MappedUpdateCtx[Ctx, Val, NewNewVal](action, nextCtx, v ⇒ fn(mapFn(v)))
}

object UpdateCtx {
  def apply[Ctx, Val](action: LeafAction[Ctx, Val])(newCtx: Val ⇒ Ctx): UpdateCtx[Ctx, Val] = new UpdateCtx(action, newCtx)
}

case class ProjectionName(name: String) extends FieldTag
case object ProjectionExclude extends FieldTag

trait Projector[Ctx, Val, Res] extends (Context[Ctx, Val] ⇒ Action[Ctx, Res]) {
  val maxLevel: Int = Integer.MAX_VALUE
  def apply(ctx: Context[Ctx, Val], projected: Vector[ProjectedName]): Action[Ctx, Res]
}

object Projector {
  def apply[Ctx, Val, Res](fn: (Context[Ctx, Val], Vector[ProjectedName]) ⇒ Action[Ctx, Res]) =
    new Projector[Ctx, Val, Res] {
      def apply(ctx: Context[Ctx, Val], projected: Vector[ProjectedName]) = fn(ctx, projected)
      override def apply(ctx: Context[Ctx, Val]) = throw new IllegalStateException("Default apply should not be called on projector!")
    }

  def apply[Ctx, Val, Res](levels: Int, fn: (Context[Ctx, Val], Vector[ProjectedName]) ⇒ Action[Ctx, Res]) =
    new Projector[Ctx, Val, Res] {
      override val maxLevel = levels
      def apply(ctx: Context[Ctx, Val], projected: Vector[ProjectedName]) = fn(ctx, projected)
      override def apply(ctx: Context[Ctx, Val]) = throw new IllegalStateException("Default apply should not be called on projector!")
    }
}

case class ProjectedName(name: String, children: Vector[ProjectedName] = Vector.empty) {
  lazy val asVector = {
    def loop(name: ProjectedName): Vector[Vector[String]] =
      Vector(name.name) +: (name.children flatMap loop map (name.name +: _))

    loop(this)
  }
}

trait Deferred[+T]

case class MappingDeferred[A, +B](deferred: Deferred[A], mapFn: A ⇒ B) extends Deferred[B]

trait WithArguments {
  def args: Args
  def arg[T](arg: Argument[T]): T = args.arg(arg)
  def arg[T](name: String): T = args.arg(name)
  def argOpt[T](name: String): Option[T] = args.argOpt(name)
}

trait WithInputTypeRendering[Ctx] {
  def ctx: Ctx
  def sourceMapper: Option[SourceMapper]
  def deprecationTracker: DeprecationTracker
  def marshaller: ResultMarshaller

  private lazy val coercionHelper = new ValueCoercionHelper[Ctx](sourceMapper, deprecationTracker, Some(ctx))

  def renderInputValueCompact[T](value: (_, ToInput[_, _]), tpe: InputType[T]): String =
    DefaultValueRenderer.renderInputValueCompact(value, tpe, coercionHelper)
}

case class DefaultValueParser[T](schema: Schema[_, _], parser: InputParser[T], toInput: ToInput[T, _])

object DefaultValueParser {
  def forType[T](schema: Schema[_, _])(implicit parser: InputParser[T], toInput: ToInput[T, _]) =
    DefaultValueParser[T](schema, parser, toInput)
}

object DefaultValueRenderer {
  implicit val marshaller = sangria.marshalling.queryAst.queryAstResultMarshaller

  def renderInputValueCompact[T, Ctx](value: (_, ToInput[_, _]), tpe: InputType[T], coercionHelper: ValueCoercionHelper[Ctx]): String =
    marshaller.renderCompact(renderInputValue(value, tpe, coercionHelper))

  def renderInputValuePretty[T, Ctx](value: (_, ToInput[_, _]), tpe: InputType[T], coercionHelper: ValueCoercionHelper[Ctx]): String =
    marshaller.renderPretty(renderInputValue(value, tpe, coercionHelper))

  def renderInputValue[T, Ctx](value: (_, ToInput[_, _]), tpe: InputType[T], coercionHelper: ValueCoercionHelper[Ctx]): marshaller.Node = {
    def loop(t: InputType[_], v: Any): marshaller.Node = t match {
      case _ if v == null ⇒ marshaller.nullNode
      case s: ScalarType[Any @unchecked] ⇒ Resolver.marshalScalarValue(s.coerceOutput(v, marshaller.capabilities), marshaller, s.name, s.scalarInfo)
      case e: EnumType[Any @unchecked] ⇒ Resolver.marshalEnumValue(e.coerceOutput(v), marshaller, e.name)
      case io: InputObjectType[_] ⇒
        val mapValue = v.asInstanceOf[Map[String, Any]]

        val builder = io.fields.foldLeft(marshaller.emptyMapNode(io.fields.map(_.name))) {
          case (acc, field) if mapValue contains field.name ⇒
            marshaller.addMapNodeElem(acc, field.name, loop(field.fieldType, mapValue(field.name)), optional = false)
          case (acc, _) ⇒ acc
        }

        marshaller.mapNode(builder)
      case l: ListInputType[_] ⇒
        val listValue = v.asInstanceOf[Seq[Any]]

        marshaller.mapAndMarshal[Any](listValue, loop(l.ofType, _))
      case o: OptionInputType[_] ⇒ v match {
        case Some(optVal) ⇒ loop(o.ofType, optVal)
        case None ⇒ marshaller.nullNode
        case other ⇒ loop(o.ofType, other)
      }
    }

    val (v, toInput) = value.asInstanceOf[(Any, ToInput[Any, Any])]
    val (inputValue, iu) = toInput.toInput(v)

    coercionHelper.coerceInputValue(tpe, Nil, inputValue, None, CoercedScalaResultMarshaller.default, CoercedScalaResultMarshaller.default)(iu) match {
      case Right(Some(coerced)) ⇒ renderCoercedInputValue(tpe, coerced)
      case _ ⇒ marshaller.nullNode
    }
  }

  def renderCoercedInputValueCompact[T](value: Any, tpe: InputType[T]): String =
    marshaller.renderCompact(renderCoercedInputValue(tpe, value))

  def renderCoercedInputValuePretty[T](value: Any, tpe: InputType[T]): String =
    marshaller.renderPretty(renderCoercedInputValue(tpe, value))

  def renderCoercedInputValue(t: InputType[_], v: Any): marshaller.Node = t match {
    case _ if v == null ⇒ marshaller.nullNode
    case s: ScalarType[Any @unchecked] ⇒ Resolver.marshalScalarValue(s.coerceOutput(v, marshaller.capabilities), marshaller, s.name, s.scalarInfo)
    case e: EnumType[Any @unchecked] ⇒ Resolver.marshalEnumValue(e.coerceOutput(v), marshaller, e.name)
    case io: InputObjectType[_] ⇒
      val mapValue = v.asInstanceOf[Map[String, Any]]

      val builder = io.fields.foldLeft(marshaller.emptyMapNode(io.fields.map(_.name))) {
        case (acc, field) if mapValue contains field.name ⇒
          marshaller.addMapNodeElem(acc, field.name, renderCoercedInputValue(field.fieldType, mapValue(field.name)), optional = false)
        case (acc, _) ⇒ acc
      }

      marshaller.mapNode(builder)
    case l: ListInputType[_] ⇒
      val listValue = v.asInstanceOf[Seq[Any]]

      marshaller.mapAndMarshal[Any](listValue, renderCoercedInputValue(l.ofType, _))
    case o: OptionInputType[_] ⇒ v match {
      case Some(optVal) ⇒ renderCoercedInputValue(o.ofType, optVal)
      case None ⇒ marshaller.nullNode
      case other ⇒ renderCoercedInputValue(o.ofType, other)
    }
  }
}

case class Context[Ctx, Val](
  value: Val,
  ctx: Ctx,
  args: Args,
  schema: Schema[Ctx, Val],
  field: Field[Ctx, Val],
  parentType: ObjectType[Ctx, Any],
  marshaller: ResultMarshaller,
  sourceMapper: Option[SourceMapper],
  deprecationTracker: DeprecationTracker,
  astFields: Vector[ast.Field],
  path: ExecutionPath) extends WithArguments with WithInputTypeRendering[Ctx]

case class Args(raw: Map[String, Any]) extends AnyVal {
  def arg[T](arg: Argument[T]): T = raw.get(arg.name).fold(None.asInstanceOf[T])(_.asInstanceOf[T])
  def arg[T](name: String): T = raw(name).asInstanceOf[T]
  def argOpt[T](name: String): Option[T] = raw.get(name).asInstanceOf[Option[Option[T]]].flatten
}

object Args {
  val empty = Args(Map.empty)
}

case class DirectiveContext(selection: ast.WithDirectives, directive: Directive, args: Args) extends WithArguments

trait DeferredResolver[-Ctx] {
  def resolve(deferred: Vector[Deferred[Any]], ctx: Ctx): Vector[Future[Any]]
}

object DeferredResolver {
  val empty = new DeferredResolver[Any] {
    override def resolve(deferred: Vector[Deferred[Any]], ctx: Any) = deferred map (_ ⇒ Future.failed(UnsupportedDeferError))
  }
}

case object UnsupportedDeferError extends Exception
