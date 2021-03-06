package sangria.schema

import sangria.ast
import sangria.ast.SchemaDefinition
import sangria.execution.FieldTag
import sangria.marshalling.{FromInput, ToInput, MarshallerCapability, ScalarValueInfo}
import sangria.validation.Violation

trait AstSchemaBuilder[Ctx] {
  def additionalTypeDefs: List[ast.TypeDefinition]
  def additionalTypeExtensionDefs: List[ast.TypeExtensionDefinition]
  def additionalDirectiveDefs: List[ast.DirectiveDefinition]

  def buildSchema(
    definition: ast.SchemaDefinition,
    queryType: ObjectType[Ctx, Any],
    mutationType: Option[ObjectType[Ctx, Any]],
    subscriptionType: Option[ObjectType[Ctx, Any]],
    additionalTypes: List[Type with Named],
    directives: List[Directive],
    mat: AstSchemaMaterializer[Ctx]): Schema[Ctx, Any]

  def buildObjectType(
    definition: ast.ObjectTypeDefinition,
    extensions: List[ast.TypeExtensionDefinition],
    fields: () ⇒ List[Field[Ctx, Any]],
    interfaces: List[InterfaceType[Ctx, Any]],
    mat: AstSchemaMaterializer[Ctx]): Option[ObjectType[Ctx, Any]]

  def buildInputObjectType(
    definition: ast.InputObjectTypeDefinition,
    fields: () ⇒ List[InputField[_]],
    mat: AstSchemaMaterializer[Ctx]): Option[InputObjectType[InputObjectType.DefaultInput]]

  def buildInterfaceType(
    definition: ast.InterfaceTypeDefinition,
    extensions: List[ast.TypeExtensionDefinition],
    fields: () ⇒ List[Field[Ctx, Any]],
    mat: AstSchemaMaterializer[Ctx]): Option[InterfaceType[Ctx, Any]]

  def buildUnionType(
    definition: ast.UnionTypeDefinition,
    types: List[ObjectType[Ctx, _]],
    mat: AstSchemaMaterializer[Ctx]): Option[UnionType[Ctx]]

  def buildScalarType(
    definition: ast.ScalarTypeDefinition,
    mat: AstSchemaMaterializer[Ctx]): Option[ScalarType[Any]]

  def buildEnumType(
    definition: ast.EnumTypeDefinition,
    values: List[EnumValue[Any]],
    mat: AstSchemaMaterializer[Ctx]): Option[EnumType[Any]]

  def buildField(
    typeDefinition: ast.TypeDefinition,
    definition: ast.FieldDefinition,
    fieldType: OutputType[_],
    arguments: List[Argument[_]],
    mat: AstSchemaMaterializer[Ctx]): Option[Field[Ctx, Any]]

  def buildInputField(
    typeDefinition: ast.InputObjectTypeDefinition,
    definition: ast.InputValueDefinition,
    tpe: InputType[_],
    defaultValue: Option[(_, ToInput[_, _])],
    mat: AstSchemaMaterializer[Ctx]): Option[InputField[Any]]

  def buildArgument(
    typeDefinition: ast.TypeSystemDefinition,
    fieldDefinition: Option[ast.FieldDefinition],
    definition: ast.InputValueDefinition,
    tpe: InputType[_],
    defaultValue: Option[(_, ToInput[_, _])],
    mat: AstSchemaMaterializer[Ctx]): Option[Argument[Any]]

  def buildEnumValue(
    typeDefinition: ast.EnumTypeDefinition,
    definition: ast.EnumValueDefinition,
    mat: AstSchemaMaterializer[Ctx]): Option[EnumValue[Any]]

  def buildDirective(
    definition: ast.DirectiveDefinition,
    arguments: List[Argument[_]],
    locations: Set[DirectiveLocation.Value],
    mat: AstSchemaMaterializer[Ctx]): Option[Directive]
}

object AstSchemaBuilder {
  def default[Ctx] = new DefaultAstSchemaBuilder[Ctx]

  def extractDescription(comment: Seq[String]): Option[String] = {
    val descrLines = comment.filter(_.startsWith("#"))

    if (descrLines.nonEmpty)
      Some(descrLines.map(_.substring(1).trim) mkString "\n")
    else
      None
  }
}

class DefaultAstSchemaBuilder[Ctx] extends AstSchemaBuilder[Ctx] {
  def additionalDirectiveDefs = Nil
  def additionalTypeExtensionDefs = Nil
  def additionalTypeDefs = Nil

  def buildSchema(
      definition: ast.SchemaDefinition,
      queryType: ObjectType[Ctx, Any],
      mutationType: Option[ObjectType[Ctx, Any]],
      subscriptionType: Option[ObjectType[Ctx, Any]],
      additionalTypes: List[Type with Named],
      directives: List[Directive],
      mat: AstSchemaMaterializer[Ctx]) =
    Schema[Ctx, Any](
      query = queryType,
      mutation = mutationType,
      subscription = subscriptionType,
      additionalTypes = additionalTypes,
      directives = directives)

  def buildObjectType(
      definition: ast.ObjectTypeDefinition,
      extensions: List[ast.TypeExtensionDefinition],
      fields: () ⇒ List[Field[Ctx, Any]],
      interfaces: List[InterfaceType[Ctx, Any]],
      mat: AstSchemaMaterializer[Ctx]) = {
    val objectType =
      objectTypeInstanceCheck(definition, extensions) match {
        case Some(fn) ⇒
          new ObjectType[Ctx, Any](
              name = typeName(definition),
              description = typeDescription(definition),
              fieldsFn = Named.checkObjFields(fields),
              interfaces = interfaces) {
            override def isInstanceOf(value: Any) = fn(value, valClass)
          }
        case None ⇒
          ObjectType[Ctx, Any](
            name = typeName(definition),
            description = typeDescription(definition),
            fieldsFn = Named.checkObjFields(fields),
            interfaces = interfaces)
      }

    Some(objectType)
  }

  def buildInputObjectType(
      definition: ast.InputObjectTypeDefinition,
      fields: () ⇒ List[InputField[_]],
      mat: AstSchemaMaterializer[Ctx]) =
    Some(InputObjectType(
      name = typeName(definition),
      description = typeDescription(definition),
      fieldsFn = Named.checkIntFields(fields)))

  def buildInterfaceType(
      definition: ast.InterfaceTypeDefinition,
      extensions: List[ast.TypeExtensionDefinition],
      fields: () ⇒ List[Field[Ctx, Any]],
      mat: AstSchemaMaterializer[Ctx]) =
    Some(InterfaceType[Ctx, Any](
      name = typeName(definition),
      description = typeDescription(definition),
      fieldsFn = Named.checkIntFields(fields),
      interfaces = Nil,
      manualPossibleTypes = () ⇒ Nil))

  def buildUnionType(
      definition: ast.UnionTypeDefinition,
      types: List[ObjectType[Ctx, _]],
      mat: AstSchemaMaterializer[Ctx]) =
    Some(UnionType[Ctx](
      name = typeName(definition),
      description = typeDescription(definition),
      types = types))

  def buildScalarType(
      definition: ast.ScalarTypeDefinition,
      mat: AstSchemaMaterializer[Ctx]) =
    Some(ScalarType[Any](
      name = typeName(definition),
      description = typeDescription(definition),
      coerceUserInput = scalarCoerceUserInput(definition),
      coerceOutput = scalarCoerceOutput(definition),
      coerceInput = scalarCoerceInput(definition),
      complexity = scalarComplexity(definition),
      scalarInfo = scalarValueInfo(definition)))

  def buildEnumType(
      definition: ast.EnumTypeDefinition,
      values: List[EnumValue[Any]],
      mat: AstSchemaMaterializer[Ctx]) =
    Some(EnumType[Any](
      name = typeName(definition),
      description = typeDescription(definition),
      values = values))

  def buildEnumValue(
      typeDefinition: ast.EnumTypeDefinition,
      definition: ast.EnumValueDefinition,
      mat: AstSchemaMaterializer[Ctx]) =
    Some(EnumValue[String](
      name = enumValueName(definition),
      description = enumValueDescription(definition),
      value = enumValue(definition),
      deprecationReason = enumValueDeprecationReason(definition)))

  def buildField(
      typeDefinition: ast.TypeDefinition,
      definition: ast.FieldDefinition,
      fieldType: OutputType[_],
      arguments: List[Argument[_]],
      mat: AstSchemaMaterializer[Ctx]) =
    Some(Field[Ctx, Any](
      name = fieldName(definition),
      description = fieldDescription(definition),
      fieldType = fieldType,
      arguments = arguments,
      resolve = resolveField(typeDefinition, definition),
      tags = fieldTags(typeDefinition, definition),
      deprecationReason = fieldDeprecationReason(definition),
      complexity = fieldComplexity(typeDefinition, definition),
      manualPossibleTypes = () ⇒ Nil))

  def buildInputField(
      typeDefinition: ast.InputObjectTypeDefinition,
      definition: ast.InputValueDefinition,
      tpe: InputType[_],
      defaultValue: Option[(_, ToInput[_, _])],
      mat: AstSchemaMaterializer[Ctx]) =
    Some(InputField(
      name = inputFieldName(definition),
      description = inputFieldDescription(definition),
      fieldType = tpe,
      defaultValue = defaultValue))

  def buildArgument(
      typeDefinition: ast.TypeSystemDefinition,
      fieldDefinition: Option[ast.FieldDefinition],
      definition: ast.InputValueDefinition,
      tpe: InputType[_],
      defaultValue: Option[(_, ToInput[_, _])],
      mat: AstSchemaMaterializer[Ctx]) =
    Some(Argument(
      name = argumentName(definition),
      description = argumentDescription(definition),
      argumentType = tpe,
      defaultValue = defaultValue,
      fromInput = argumentFromInput(typeDefinition, fieldDefinition, definition)))

  def buildDirective(
      definition: ast.DirectiveDefinition,
      arguments: List[Argument[_]],
      locations: Set[DirectiveLocation.Value],
      mat: AstSchemaMaterializer[Ctx]) =
    Some(Directive(
      name = directiveName(definition),
      description = directiveDescription(definition),
      locations = locations,
      arguments = arguments,
      shouldInclude = directiveShouldInclude(definition)))

  def objectTypeInstanceCheck(definition: ast.ObjectTypeDefinition, extensions: List[ast.TypeExtensionDefinition]): Option[(Any, Class[_]) ⇒ Boolean] =
    None

  def directiveShouldInclude(definition: ast.DirectiveDefinition): DirectiveContext ⇒ Boolean =
    Function.const(true)

  def argumentFromInput(
      typeDefinition: ast.TypeSystemDefinition,
      fieldDefinition: Option[ast.FieldDefinition],
      definition: ast.InputValueDefinition) =
    FromInput.defaultInput[Any]

  def resolveField(typeDefinition: ast.TypeDefinition, definition: ast.FieldDefinition): Context[Ctx, _] ⇒ Action[Ctx, _] =
    (ctx) ⇒ throw DefaultIntrospectionSchemaBuilder.MaterializedSchemaException

  def fieldTags(typeDefinition: ast.TypeDefinition, definition: ast.FieldDefinition): List[FieldTag] =
    Nil

  def scalarCoerceUserInput(definition: ast.ScalarTypeDefinition): Any ⇒ Either[Violation, Any] =
    _ ⇒ Left(DefaultIntrospectionSchemaBuilder.MaterializedSchemaViolation)

  def scalarCoerceInput(definition: ast.ScalarTypeDefinition): ast.Value ⇒ Either[Violation, Any] =
    _ ⇒ Left(DefaultIntrospectionSchemaBuilder.MaterializedSchemaViolation)

  def scalarCoerceOutput(definition: ast.ScalarTypeDefinition): (Any, Set[MarshallerCapability]) ⇒ Any =
    (_, _) ⇒ throw DefaultIntrospectionSchemaBuilder.MaterializedSchemaException

  def scalarValueInfo(definition: ast.ScalarTypeDefinition): Set[ScalarValueInfo] =
    Set.empty

  def scalarComplexity(definition: ast.ScalarTypeDefinition): Double =
    0.0D

  def fieldComplexity(typeDefinition: ast.TypeDefinition, definition: ast.FieldDefinition): Option[(Ctx, Args, Double) ⇒ Double] =
    None

  def enumValueDeprecationReason(definition: ast.EnumValueDefinition): Option[String] =
    deprecationReason(definition.directives)

  def fieldDeprecationReason(definition: ast.FieldDefinition): Option[String] =
    deprecationReason(definition.directives)

  def deprecationReason(dirs: List[ast.Directive]): Option[String] =
    dirs.find(_.name == DeprecatedDirective.name).flatMap { d ⇒
      d.arguments.find(_.name == ReasonArg.name) match {
        case Some(reason) ⇒
          reason.value match {
            case ast.StringValue(value, _, _) ⇒ Some(value)
            case _ ⇒ None
          }
        case None ⇒ Some(DefaultDeprecationReason)
      }
    }

  def typeName(definition: ast.TypeDefinition): String =
    Named.checkName(definition.name)

  def fieldName(definition: ast.FieldDefinition): String =
    Named.checkName(definition.name)

  def enumValueName(definition: ast.EnumValueDefinition): String =
    Named.checkName(definition.name)

  def argumentName(definition: ast.InputValueDefinition): String =
    Named.checkName(definition.name)

  def inputFieldName(definition: ast.InputValueDefinition): String =
    Named.checkName(definition.name)

  def directiveName(definition: ast.DirectiveDefinition): String =
    Named.checkName(definition.name)

  def commentDescription(comment: Option[ast.Comment]): Option[String] =
    comment flatMap (c ⇒ AstSchemaBuilder.extractDescription(c.lines))

  def typeDescription(definition: ast.TypeDefinition): Option[String] =
    commentDescription(definition.comment)

  def fieldDescription(definition: ast.FieldDefinition): Option[String] =
    commentDescription(definition.comment)

  def argumentDescription(definition: ast.InputValueDefinition): Option[String] =
    commentDescription(definition.comment)

  def inputFieldDescription(definition: ast.InputValueDefinition): Option[String] =
    commentDescription(definition.comment)

  def enumValueDescription(definition: ast.EnumValueDefinition): Option[String] =
    commentDescription(definition.comment)

  def directiveDescription(definition: ast.DirectiveDefinition): Option[String] =
    commentDescription(definition.comment)

  def enumValue(definition: ast.EnumValueDefinition): String =
    definition.name
}