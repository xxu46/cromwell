package cromwell.backend.validation

import cats.data.{NonEmptyList, Validated}
import cats.syntax.validated._
import cromwell.backend.{MemorySize, RuntimeAttributeDefinition}
import lenthall.validation.ErrorOr._
import org.slf4j.Logger
import wdl4s.expression.PureStandardLibraryFunctions
import wdl4s.types.{WdlBooleanType, WdlIntegerType, WdlType}
import wdl4s.values._
import wdl4s.{NoLookup, WdlExpression}

import scala.util.{Failure, Success}

object RuntimeAttributesValidation {

  def warnUnrecognized(actual: Set[String], expected: Set[String], logger: Logger): Unit = {
    val unrecognized = actual.diff(expected).mkString(", ")
    if (unrecognized.nonEmpty) logger.warn(s"Unrecognized runtime attribute keys: $unrecognized")
  }

  def validateDocker(docker: Option[WdlValue], onMissingKey: => ErrorOr[Option[String]]): ErrorOr[Option[String]] = {
    validateWithValidation(docker, DockerValidation.optional, onMissingKey)
  }

  def validateFailOnStderr(value: Option[WdlValue], onMissingKey: => ErrorOr[Boolean]): ErrorOr[Boolean] = {
    validateWithValidation(value, FailOnStderrValidation.default, onMissingKey)
  }

  def validateContinueOnReturnCode(value: Option[WdlValue],
                                   onMissingKey: => ErrorOr[ContinueOnReturnCode]): ErrorOr[ContinueOnReturnCode] = {
    validateWithValidation(value, ContinueOnReturnCodeValidation.default, onMissingKey)
  }

  def validateMemory(value: Option[WdlValue], onMissingKey: => ErrorOr[MemorySize]): ErrorOr[MemorySize] = {
    validateWithValidation(value, MemoryValidation.instance, onMissingKey)
  }

  def validateCpu(cpu: Option[WdlValue], onMissingKey: => ErrorOr[Int]): ErrorOr[Int] = {
    validateWithValidation(cpu, CpuValidation.default, onMissingKey)
  }

  private def validateWithValidation[T](valueOption: Option[WdlValue],
                                        validation: RuntimeAttributesValidation[T],
                                        onMissingValue: => ErrorOr[T]): ErrorOr[T] = {
    valueOption match {
      case Some(value) =>
        validation.validateValue.applyOrElse(value, (_: Any) => validation.invalidValueFailure(value))
      case None => onMissingValue
    }
  }

  def validateInt(value: WdlValue): ErrorOr[Int] = {
    WdlIntegerType.coerceRawValue(value) match {
      case scala.util.Success(WdlInteger(i)) => i.intValue.validNel
      case _ => s"Could not coerce ${value.valueString} into an integer".invalidNel
    }
  }

  def validateBoolean(value: WdlValue): ErrorOr[Boolean] = {
    WdlBooleanType.coerceRawValue(value) match {
      case scala.util.Success(WdlBoolean(b)) => b.booleanValue.validNel
      case _ => s"Could not coerce ${value.valueString} into a boolean".invalidNel
    }
  }

  def parseMemoryString(s: WdlString): ErrorOr[MemorySize] = {
    MemoryValidation.validateMemoryString(s)
  }

  def parseMemoryInteger(i: WdlInteger): ErrorOr[MemorySize] = {
    MemoryValidation.validateMemoryInteger(i)
  }

  def withDefault[ValidatedType](validation: RuntimeAttributesValidation[ValidatedType],
                                 default: WdlValue): RuntimeAttributesValidation[ValidatedType] = {
    new RuntimeAttributesValidation[ValidatedType] {
      override def key: String = validation.key

      override def coercion: Traversable[WdlType] = validation.coercion

      override protected def validateValue: PartialFunction[WdlValue, ErrorOr[ValidatedType]] =
        validation.validateValuePackagePrivate

      override protected def validateExpression: PartialFunction[WdlValue, Boolean] =
        validation.validateExpressionPackagePrivate

      override protected def invalidValueMessage(value: WdlValue): String =
        validation.invalidValueMessagePackagePrivate(value)

      override protected def missingValueMessage: String = validation.missingValueMessage

      override protected def usedInCallCaching: Boolean = validation.usedInCallCachingPackagePrivate

      override protected def staticDefaultOption = Option(default)
    }
  }

  def optional[ValidatedType](validation: RuntimeAttributesValidation[ValidatedType]):
  OptionalRuntimeAttributesValidation[ValidatedType] = {
    new OptionalRuntimeAttributesValidation[ValidatedType] {
      override def key: String = validation.key

      override def coercion: Traversable[WdlType] = validation.coercion

      override protected def validateOption: PartialFunction[WdlValue, ErrorOr[ValidatedType]] =
        validation.validateValuePackagePrivate

      override protected def validateExpression: PartialFunction[WdlValue, Boolean] =
        validation.validateExpressionPackagePrivate

      override protected def invalidValueMessage(value: WdlValue): String =
        validation.invalidValueMessagePackagePrivate(value)

      override protected def missingValueMessage: String = validation.missingValueMessage

      override protected def usedInCallCaching: Boolean = validation.usedInCallCachingPackagePrivate

      override protected def staticDefaultOption = validation.staticDefaultOption
    }
  }

  /**
    * Returns the value from the attributes, unpacking options, and converting them to string values suitable for
    * storage in metadata.
    *
    * @param validatedRuntimeAttributes The values to search.
    * @return The keys and extracted values.
    */
  def toMetadataStrings(validatedRuntimeAttributes: ValidatedRuntimeAttributes): Map[String, String] = {
    val attributeOptions: Map[String, Option[Any]] = validatedRuntimeAttributes.attributes.mapValues(unpackOption)

    val attributes: Map[String, String] = attributeOptions collect {
      case (name, Some(values: Traversable[_])) => (name, values.mkString(","))
      case (name, Some(value)) => (name, value.toString)
    }

    attributes
  }

  /**
    * Returns the value from the attributes matching the validation key.
    *
    * Do not use an optional validation as the type internal implementation will throw a `ClassCastException` due to the
    * way values are located and auto-magically cast to the type of the `runtimeAttributesValidation`.
    *
    * @param runtimeAttributesValidation The typed validation to use.
    * @param validatedRuntimeAttributes  The values to search.
    * @return The value matching the key.
    * @throws ClassCastException if the validation is called on an optional validation.
    */
  def extract[A](runtimeAttributesValidation: RuntimeAttributesValidation[A],
                 validatedRuntimeAttributes: ValidatedRuntimeAttributes): A = {
    extract(runtimeAttributesValidation.key, validatedRuntimeAttributes)
  }

  /**
    * Returns the value from the attributes matching the key.
    *
    * @param key                        The key to retrieve.
    * @param validatedRuntimeAttributes The values to search.
    * @return The value matching the key.
    */
  def extract[A](key: String,
                 validatedRuntimeAttributes: ValidatedRuntimeAttributes): A = {
    val value = extractOption(key, validatedRuntimeAttributes)
    value match {
      // NOTE: Some(innerValue) aka Some.unapply() throws a `ClassCastException` to `Nothing$` as it can't tell the type
      case some: Some[_] => some.get.asInstanceOf[A]
      case None => throw new RuntimeException(
        s"$key not found in runtime attributes ${validatedRuntimeAttributes.attributes.keys}")
    }
  }

  /**
    * Returns Some(value) from the attributes matching the validation key, or None.
    *
    * @param runtimeAttributesValidation The typed validation to use.
    * @param validatedRuntimeAttributes  The values to search.
    * @return The Some(value) matching the key or None.
    */
  def extractOption[A](runtimeAttributesValidation: RuntimeAttributesValidation[A],
                       validatedRuntimeAttributes: ValidatedRuntimeAttributes): Option[A] = {
    extractOption(runtimeAttributesValidation.key, validatedRuntimeAttributes)
  }

  /**
    * Returns Some(value) from the attributes matching the key, or None.
    *
    * @param key                        The key to retrieve.
    * @param validatedRuntimeAttributes The values to search.
    * @return The Some(value) matching the key or None.
    */
  def extractOption[A](key: String, validatedRuntimeAttributes: ValidatedRuntimeAttributes): Option[A] = {
    val value = validatedRuntimeAttributes.attributes.get(key)
    unpackOption[A](value)
  }

  /**
    * Recursively unpacks an option looking for a value of some type A.
    *
    * @param value The value to unpack.
    * @tparam A The type to cast the unpacked value.
    * @return The Some(value) matching the key or None.
    */
  final def unpackOption[A](value: Any): Option[A] = {
    value match {
      case None => None
      case Some(innerValue) => unpackOption(innerValue)
      case _ => Option(value.asInstanceOf[A])
    }
  }
}

/**
  * Performs a validation on a runtime attribute and returns some value.
  *
  * @tparam ValidatedType The type of the validated value.
  */
trait RuntimeAttributesValidation[ValidatedType] {
  /**
    * Returns the key of the runtime attribute.
    *
    * @return The key of the runtime attribute.
    */
  def key: String

  /**
    * The WDL types that will be passed to `validate`, after the value is coerced from the first element found that
    * can coerce the type.
    *
    * @return traversable of wdl types
    */
  def coercion: Traversable[WdlType]

  /**
    * Validates the wdl value.
    *
    * @return The validated value or an error, wrapped in a cats validation.
    */
  protected def validateValue: PartialFunction[WdlValue, ErrorOr[ValidatedType]]

  /**
    * Returns the value for when there is no wdl value. By default returns an error.
    *
    * @return the value for when there is no wdl value.
    */
  protected def validateNone: ErrorOr[ValidatedType] = missingValueFailure

  /**
    * Returns true if the value can be validated.
    *
    * The base implementation does a basic check that a coercion exists.
    *
    * Subclasses may inspect the wdl value for more information to identify if the value may be validated. For example,
    * the `ContinueOnReturnCodeValidation` checks that all elements in a `WdlArray` can be sub-coerced into an integer.
    *
    * @return true if the value can be validated.
    */
  protected def validateExpression: PartialFunction[WdlValue, Boolean] = {
    case wdlValue => coercion.exists(_ == wdlValue.wdlType)
  }

  /**
    * Returns the optional default value when no other is specified.
    *
    * @return the optional default value when no other is specified.
    */
  protected def staticDefaultOption: Option[WdlValue] = None

  /**
    * Returns message to return when a value is invalid.
    *
    * By default returns the missingValueMessage.
    *
    * @return Message to return when a value is invalid.
    */
  protected def invalidValueMessage(value: WdlValue): String = missingValueMessage

  /**
    * Utility method to wrap the invalidValueMessage in an ErrorOr.
    *
    * @return Wrapped invalidValueMessage.
    */
  protected final def invalidValueFailure(value: WdlValue): ErrorOr[ValidatedType] =
    invalidValueMessage(value).invalidNel

  /**
    * Returns message to return when a value is missing.
    *
    * @return Message to return when a value is missing.
    */
  protected def missingValueMessage: String = s"Expecting $key runtime attribute to be a type in $coercion"

  /**
    * Utility method to wrap the missingValueMessage in an ErrorOr.
    *
    * @return Wrapped missingValueMessage.
    */
  protected final lazy val missingValueFailure: ErrorOr[ValidatedType] = missingValueMessage.invalidNel

  /**
    * Runs this validation on the value matching key.
    *
    * NOTE: The values passed to this method should already be evaluated instances of WdlValue, and not WdlExpression.
    *
    * @param values The full set of values.
    * @return The error or valid value for this key.
    */
  def validate(values: Map[String, WdlValue]): ErrorOr[ValidatedType] = {
    values.get(key) match {
      case Some(value) => validateValue.applyOrElse(value, (_: Any) => invalidValueFailure(value))
      case None => validateNone
    }
  }

  /**
    * Used during initialization, returning true if the expression __may be__ valid.
    *
    * The `BackendWorkflowInitializationActor` requires validation be performed by a map of validating functions:
    *
    * {{{
    * runtimeAttributeValidators: Map[String, Option[WdlExpression] => Boolean]
    * }}}
    *
    * With our `key` as the key in the map, one can return this function as the value in the map.
    *
    * NOTE: If there is an attempt lookup a value within a WdlExpression, or a WdlExpression fails to evaluate for any
    * reason, this method will simply return true.
    *
    * @param wdlExpressionMaybe The optional expression.
    * @return True if the expression may be evaluated.
    */
  def validateOptionalExpression(wdlExpressionMaybe: Option[WdlValue]): Boolean = {
    wdlExpressionMaybe match {
      case None => staticDefaultOption.isDefined || validateNone.isValid
      case Some(wdlExpression: WdlExpression) =>
        wdlExpression.evaluate(NoLookup, PureStandardLibraryFunctions) match {
          case Success(wdlValue) => validateExpression.applyOrElse(wdlValue, (_: Any) => false)
          case Failure(_) => true // If we can't evaluate it, we'll let it pass for now...
        }
      case Some(wdlValue) => validateExpression.applyOrElse(wdlValue, (_: Any) => false)
    }
  }

  /**
    * Used to convert this instance to a `RuntimeAttributeDefinition`.
    *
    * @see [[RuntimeAttributeDefinition.usedInCallCaching]]
    * @return Value for [[RuntimeAttributeDefinition.usedInCallCaching]].
    */
  protected def usedInCallCaching: Boolean = false

  /**
    * Returns this as an instance of a runtime attribute definition.
    */
  final lazy val runtimeAttributeDefinition = RuntimeAttributeDefinition(key, staticDefaultOption, usedInCallCaching)

  /**
    * Returns an optional version of this validation.
    */
  final lazy val optional: OptionalRuntimeAttributesValidation[ValidatedType] =
  RuntimeAttributesValidation.optional(this)

  /**
    * Returns a version of this validation with the default value.
    *
    * @param wdlValue The default wdl value.
    * @return The new version of this validation.
    */
  final def withDefault(wdlValue: WdlValue): RuntimeAttributesValidation[ValidatedType] =
    RuntimeAttributesValidation.withDefault(this, wdlValue)

  /*
  Methods below provide aliases to expose protected methods to the package.
  Allows wrappers to wire their overrides to invoke the corresponding method on the inner object.
  The protected methods are only available to subclasses, or this trait. Now, no one outside this trait lineage can
  access the protected values, except the `validation` package that uses these back doors.
   */

  private[validation] final lazy val validateValuePackagePrivate = validateValue

  private[validation] final lazy val validateExpressionPackagePrivate = validateExpression

  private[validation] final def invalidValueMessagePackagePrivate(value: WdlValue) = invalidValueMessage(value)

  private[validation] final lazy val missingValueMessagePackagePrivate = missingValueMessage

  private[validation] final lazy val usedInCallCachingPackagePrivate = usedInCallCaching
}

/**
  * An optional version of a runtime attribute validation.
  *
  * @tparam ValidatedType The type of the validated value.
  */
trait OptionalRuntimeAttributesValidation[ValidatedType] extends RuntimeAttributesValidation[Option[ValidatedType]] {
  /**
    * Validates the wdl value.
    *
    * This method is the same as `validateValue`, but allows the implementor to not have to wrap the response in an
    * `Option`.
    *
    * @return The validated value or an error, wrapped in a cats validation.
    */
  protected def validateOption: PartialFunction[WdlValue, ErrorOr[ValidatedType]]

  override final protected lazy val validateValue = new PartialFunction[WdlValue, ErrorOr[Option[ValidatedType]]] {
    override def isDefinedAt(wdlValue: WdlValue): Boolean = validateOption.isDefinedAt(wdlValue)

    override def apply(wdlValue: WdlValue): Validated[NonEmptyList[String], Option[ValidatedType]] = {
      validateOption.apply(wdlValue).map(Option.apply)
    }
  }

  override final protected lazy val validateNone: ErrorOr[None.type] = None.validNel[String]
}
