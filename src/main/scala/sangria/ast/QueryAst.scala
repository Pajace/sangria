package sangria.ast

import org.parboiled2.Position
import sangria.parser.SourceMapper

import scala.collection.immutable.ListMap

case class Document(definitions: List[Definition], position: Option[Position] = None, sourceMapper: Option[SourceMapper] = None) extends AstNode {
  lazy val operations = Map(definitions collect {case op: OperationDefinition ⇒ op.name → op}: _*)
  lazy val fragments = Map(definitions collect {case fragment: FragmentDefinition ⇒ fragment.name → fragment}: _*)
  lazy val source = sourceMapper map (_.source)

  def operationType(operationName: Option[String] = None): Option[OperationType] =
    operation(operationName) map (_.operationType)

  def operation(operationName: Option[String] = None): Option[OperationDefinition] =
    if (operations.size != 1 && operationName.isEmpty)
      None
    else
      operationName flatMap (opName ⇒ operations get Some(opName)) orElse operations.values.headOption

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Document]

  override def equals(other: Any): Boolean = other match {
    case that: Document ⇒
      (that canEqual this) &&
        definitions == that.definitions &&
        position == that.position
    case _ ⇒ false
  }

  override def hashCode(): Int =
    Seq(definitions, position).map(_.hashCode()).foldLeft(0)((a, b) ⇒ 31 * a + b)
}

object Document {
  /**
    * Provided a collection of ASTs, presumably each from different files,
    * concatenate the ASTs together into batched AST, useful for validating many
    * GraphQL source files which together represent one conceptual application.
    *
    * The result of the merge will loose the `sourceMapper` and `position` since
    * connection to the original string source is lost.
    */
  def merge(documents: Traversable[Document]): Document =
    Document(documents.toList.flatMap(_.definitions))
}

sealed trait ConditionalFragment extends AstNode {
  def typeConditionOpt: Option[NamedType]
}

sealed trait SelectionContainer extends AstNode {
  def selections: List[Selection]
  def comment: Option[Comment]
  def position: Option[Position]
}

sealed trait Definition extends AstNode

case class OperationDefinition(
  operationType: OperationType = OperationType.Query,
  name: Option[String] = None,
  variables: List[VariableDefinition] = Nil,
  directives: List[Directive] = Nil,
  selections: List[Selection],
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends Definition with WithDirectives with SelectionContainer

case class FragmentDefinition(
    name: String,
    typeCondition: NamedType,
    directives: List[Directive],
    selections: List[Selection],
    comment: Option[Comment] = None,
    position: Option[Position] = None) extends Definition with ConditionalFragment with WithDirectives with SelectionContainer {
  lazy val typeConditionOpt = Some(typeCondition)
}

sealed trait OperationType

object OperationType {
  case object Query extends OperationType
  case object Mutation extends OperationType
  case object Subscription extends OperationType
}

case class VariableDefinition(
  name: String,
  tpe: Type,
  defaultValue: Option[Value],
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends AstNode

sealed trait Type extends AstNode

case class NamedType(name: String, position: Option[Position] = None) extends Type
case class NotNullType(ofType: Type, position: Option[Position] = None) extends Type
case class ListType(ofType: Type, position: Option[Position] = None) extends Type

sealed trait Selection extends AstNode with WithDirectives {
  def comment: Option[Comment]
}

case class Field(
    alias: Option[String],
    name: String,
    arguments: List[Argument],
    directives: List[Directive],
    selections: List[Selection],
    comment: Option[Comment] = None,
    position: Option[Position] = None) extends Selection with SelectionContainer {
  lazy val outputName = alias getOrElse name
}

case class FragmentSpread(
  name: String,
  directives: List[Directive],
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends Selection

case class InlineFragment(
    typeCondition: Option[NamedType],
    directives: List[Directive],
    selections: List[Selection],
    comment: Option[Comment] = None,
    position: Option[Position] = None) extends Selection with ConditionalFragment with SelectionContainer {
  def typeConditionOpt = typeCondition
}

sealed trait NameValue extends AstNode {
  def name: String
  def value: Value
}

sealed trait WithDirectives {
  def directives: List[Directive]
}

case class Directive(name: String, arguments: List[Argument], comment: Option[Comment] = None, position: Option[Position] = None) extends AstNode
case class Argument(name: String, value: Value, comment: Option[Comment] = None, position: Option[Position] = None) extends NameValue

sealed trait Value extends AstNode {
  def comment: Option[Comment]
}

sealed trait ScalarValue extends Value

case class IntValue(value: Int, comment: Option[Comment] = None, position: Option[Position] = None) extends ScalarValue
case class BigIntValue(value: BigInt, comment: Option[Comment] = None, position: Option[Position] = None) extends ScalarValue
case class FloatValue(value: Double, comment: Option[Comment] = None, position: Option[Position] = None) extends ScalarValue
case class BigDecimalValue(value: BigDecimal, comment: Option[Comment] = None, position: Option[Position] = None) extends ScalarValue
case class StringValue(value: String, comment: Option[Comment] = None, position: Option[Position] = None) extends ScalarValue
case class BooleanValue(value: Boolean, comment: Option[Comment] = None, position: Option[Position] = None) extends ScalarValue
case class EnumValue(value: String, comment: Option[Comment] = None, position: Option[Position] = None) extends Value
case class ListValue(values: List[Value], comment: Option[Comment] = None, position: Option[Position] = None) extends Value
case class VariableValue(name: String, comment: Option[Comment] = None, position: Option[Position] = None) extends Value
case class NullValue(comment: Option[Comment] = None, position: Option[Position] = None) extends Value
case class ObjectValue(fields: List[ObjectField], comment: Option[Comment] = None, position: Option[Position] = None) extends Value {
  lazy val fieldsByName =
    fields.foldLeft(ListMap.empty[String, Value]) {
      case (acc, field) ⇒ acc + (field.name → field.value)
    }
}

case class ObjectField(name: String, value: Value, comment: Option[Comment] = None, position: Option[Position] = None) extends NameValue

case class Comment(lines: Seq[String], position: Option[Position] = None) extends AstNode

// Schema Definition

case class ScalarTypeDefinition(
  name: String,
  directives: List[Directive] = Nil,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends TypeDefinition

case class FieldDefinition(
  name: String,
  fieldType: Type,
  arguments: List[InputValueDefinition],
  directives: List[Directive] = Nil,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends SchemaAstNode

case class InputValueDefinition(
  name: String,
  valueType: Type,
  defaultValue: Option[Value],
  directives: List[Directive] = Nil,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends SchemaAstNode

case class ObjectTypeDefinition(
  name: String,
  interfaces: List[NamedType],
  fields: List[FieldDefinition],
  directives: List[Directive] = Nil,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends TypeDefinition

case class InterfaceTypeDefinition(
  name: String,
  fields: List[FieldDefinition],
  directives: List[Directive] = Nil,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends TypeDefinition

case class UnionTypeDefinition(
  name: String,
  types: List[NamedType],
  directives: List[Directive] = Nil,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends TypeDefinition

case class EnumTypeDefinition(
  name: String,
  values: List[EnumValueDefinition],
  directives: List[Directive] = Nil,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends TypeDefinition

case class EnumValueDefinition(
  name: String,
  directives: List[Directive] = Nil,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends SchemaAstNode

case class InputObjectTypeDefinition(
  name: String,
  fields: List[InputValueDefinition],
  directives: List[Directive] = Nil,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends TypeDefinition

case class TypeExtensionDefinition(
  definition: ObjectTypeDefinition,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends TypeSystemDefinition

case class DirectiveDefinition(
  name: String,
  arguments: List[InputValueDefinition],
  locations: List[DirectiveLocation],
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends TypeSystemDefinition

case class DirectiveLocation(
  name: String,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends SchemaAstNode

case class SchemaDefinition(
  operationTypes: List[OperationTypeDefinition],
  directives: List[Directive] = Nil,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends TypeSystemDefinition

case class OperationTypeDefinition(
  operation: OperationType,
  tpe: NamedType,
  comment: Option[Comment] = None,
  position: Option[Position] = None) extends SchemaAstNode

sealed trait AstNode {
  def position: Option[Position]
  def cacheKeyHash: Int = System.identityHashCode(this)
}

sealed trait SchemaAstNode extends AstNode
sealed trait TypeSystemDefinition extends SchemaAstNode with Definition
sealed trait TypeDefinition extends TypeSystemDefinition {
  def name: String
  def comment: Option[Comment]
  def directives: List[Directive]
}

object AstNode {
  def withoutPosition[T <: AstNode](node: T, stripComments: Boolean = false): T = node match {
    case n: Document ⇒ n.copy(definitions = n.definitions map (withoutPosition(_, stripComments)), position = None, sourceMapper = None).asInstanceOf[T]
    case n: OperationDefinition ⇒
      n.copy(
        variables = n.variables map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        selections = n.selections map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: FragmentDefinition ⇒
      n.copy(
        typeCondition = withoutPosition(n.typeCondition),
        directives = n.directives map (withoutPosition(_, stripComments)),
        selections = n.selections map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: VariableDefinition ⇒
      n.copy(
        tpe = withoutPosition(n.tpe, stripComments),
        defaultValue = n.defaultValue map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: NamedType ⇒ n.copy(position = None).asInstanceOf[T]
    case n: NotNullType ⇒ n.copy(ofType = withoutPosition(n.ofType, stripComments), position = None).asInstanceOf[T]
    case n: ListType ⇒ n.copy(ofType = withoutPosition(n.ofType, stripComments), position = None).asInstanceOf[T]
    case n: Field ⇒
      n.copy(
        arguments = n.arguments map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        selections = n.selections map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: FragmentSpread ⇒
      n.copy(
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: InlineFragment ⇒
      n.copy(
        typeCondition = n.typeCondition map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        selections = n.selections map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: Directive ⇒
      n.copy(
        arguments = n.arguments map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: Argument ⇒
      n.copy(
        value = withoutPosition(n.value, stripComments),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: IntValue ⇒ 
      n.copy(
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)), 
        position = None).asInstanceOf[T]
    case n: BigIntValue ⇒ 
      n.copy(
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)), 
        position = None).asInstanceOf[T]
    case n: FloatValue ⇒ 
      n.copy(
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)), 
        position = None).asInstanceOf[T]
    case n: BigDecimalValue ⇒ 
      n.copy(
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)), 
        position = None).asInstanceOf[T]
    case n: StringValue ⇒ 
      n.copy(
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)), 
        position = None).asInstanceOf[T]
    case n: BooleanValue ⇒ 
      n.copy(
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)), 
        position = None).asInstanceOf[T]
    case n: NullValue ⇒ 
      n.copy(
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)), 
        position = None).asInstanceOf[T]
    case n: EnumValue ⇒ 
      n.copy(
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)), 
        position = None).asInstanceOf[T]
    case n: ListValue ⇒
      n.copy(
        values = n.values map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: ObjectValue ⇒
      n.copy(
        fields = n.fields map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: ObjectField ⇒
      n.copy(
        value = withoutPosition(n.value, stripComments),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: VariableValue ⇒ 
      n.copy(
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)), 
        position = None).asInstanceOf[T]
    case n: Comment ⇒ n.copy(position = None).asInstanceOf[T]

    case n: ScalarTypeDefinition ⇒
      n.copy(
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: FieldDefinition ⇒
      n.copy(
        fieldType = withoutPosition(n.fieldType, stripComments),
        arguments = n.arguments map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: InputValueDefinition ⇒
      n.copy(
        valueType = withoutPosition(n.valueType, stripComments),
        defaultValue = n.defaultValue map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: ObjectTypeDefinition ⇒
      n.copy(
        interfaces = n.interfaces map (withoutPosition(_, stripComments)),
        fields = n.fields map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: InterfaceTypeDefinition ⇒
      n.copy(
        fields = n.fields map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: UnionTypeDefinition ⇒
      n.copy(
        types = n.types map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: EnumTypeDefinition ⇒
      n.copy(
        values = n.values map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: EnumValueDefinition ⇒
      n.copy(
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: InputObjectTypeDefinition ⇒
      n.copy(
        fields = n.fields map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: TypeExtensionDefinition ⇒
      n.copy(
        definition = withoutPosition(n.definition, stripComments),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: DirectiveDefinition ⇒
      n.copy(
        arguments = n.arguments map (withoutPosition(_, stripComments)),
        locations = n.locations map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: DirectiveLocation ⇒
      n.copy(
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: SchemaDefinition ⇒
      n.copy(
        operationTypes = n.operationTypes map (withoutPosition(_, stripComments)),
        directives = n.directives map (withoutPosition(_, stripComments)),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
    case n: OperationTypeDefinition ⇒
      n.copy(
        tpe = withoutPosition(n.tpe, stripComments),
        comment = if (stripComments) None else n.comment map (withoutPosition(_, stripComments)),
        position = None).asInstanceOf[T]
  }
}