/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.calls

import org.jetbrains.kotlin.builtins.ReflectionTypes
import org.jetbrains.kotlin.builtins.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.FunctionDescriptorUtil
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.*
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.ResolveArgumentsMode.RESOLVE_FUNCTION_ARGUMENTS
import org.jetbrains.kotlin.resolve.calls.callResolverUtil.ResolveArgumentsMode.SHAPE_FUNCTION_ARGUMENTS
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.calls.callUtil.isSafeCall
import org.jetbrains.kotlin.resolve.calls.context.CallCandidateResolutionContext
import org.jetbrains.kotlin.resolve.calls.context.ContextDependency.INDEPENDENT
import org.jetbrains.kotlin.resolve.calls.context.ResolutionContext
import org.jetbrains.kotlin.resolve.calls.context.ResolutionResultsCache
import org.jetbrains.kotlin.resolve.calls.context.TemporaryTraceAndCache
import org.jetbrains.kotlin.resolve.calls.inference.*
import org.jetbrains.kotlin.resolve.calls.inference.constraintPosition.ConstraintPosition
import org.jetbrains.kotlin.resolve.calls.inference.constraintPosition.ConstraintPositionKind.RECEIVER_POSITION
import org.jetbrains.kotlin.resolve.calls.inference.constraintPosition.ConstraintPositionKind.VALUE_PARAMETER_POSITION
import org.jetbrains.kotlin.resolve.calls.resolvedCallUtil.makeNullableTypeIfSafeReceiver
import org.jetbrains.kotlin.resolve.calls.results.ResolutionStatus
import org.jetbrains.kotlin.resolve.calls.results.ResolutionStatus.INCOMPLETE_TYPE_INFERENCE
import org.jetbrains.kotlin.resolve.calls.results.ResolutionStatus.OTHER_ERROR
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.TypeUtils.DONT_CARE
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.expressions.ExpressionTypingUtils

class GenericCandidateResolver(private val argumentTypeResolver: ArgumentTypeResolver) {
    fun <D : CallableDescriptor> inferTypeArguments(context: CallCandidateResolutionContext<D>): ResolutionStatus {
        val candidateCall = context.candidateCall
        val candidate = candidateCall.candidateDescriptor

        val builder = ConstraintSystemBuilderImpl()
        builder.registerTypeVariables(candidateCall.call.toHandle(), candidate.typeParameters)

        val substituteDontCare = makeConstantSubstitutor(candidate.typeParameters, DONT_CARE)

        // Value parameters
        for ((candidateParameter, resolvedValueArgument) in candidateCall.valueArguments) {
            val valueParameterDescriptor = candidate.valueParameters[candidateParameter.index]

            for (valueArgument in resolvedValueArgument.arguments) {
                // TODO : more attempts, with different expected types

                // Here we type check expecting an error type (DONT_CARE, substitution with substituteDontCare)
                // and throw the results away
                // We'll type check the arguments later, with the inferred types expected
                addConstraintForValueArgument(
                        valueArgument, valueParameterDescriptor, substituteDontCare, builder, context, SHAPE_FUNCTION_ARGUMENTS
                )
            }
        }

        // Receiver
        // Error is already reported if something is missing
        val receiverArgument = candidateCall.extensionReceiver
        val receiverParameter = candidate.extensionReceiverParameter
        if (receiverArgument != null && receiverParameter != null) {
            val receiverArgumentType = receiverArgument.type
            var receiverType: KotlinType? = if (context.candidateCall.call.isSafeCall())
                TypeUtils.makeNotNullable(receiverArgumentType)
            else
                receiverArgumentType
            if (receiverArgument is ExpressionReceiver) {
                receiverType = updateResultTypeForSmartCasts(receiverType, receiverArgument.expression, context)
            }
            builder.addSubtypeConstraint(
                    receiverType,
                    builder.compositeSubstitutor().substitute(receiverParameter.type, Variance.INVARIANT),
                    RECEIVER_POSITION.position()
            )
        }

        val constraintSystem = builder.build()
        candidateCall.setConstraintSystem(constraintSystem)

        // Solution
        val hasContradiction = constraintSystem.status.hasContradiction()
        if (!hasContradiction) {
            return INCOMPLETE_TYPE_INFERENCE
        }
        return OTHER_ERROR
    }

    // Creates a substitutor which maps types to their representation in the constraint system.
    // In case when some type parameter descriptor is represented by more than one variable in the system, the behavior is undefined.
    private fun ConstraintSystem.Builder.compositeSubstitutor(): TypeSubstitutor {
        return TypeSubstitutor.create(object : TypeSubstitution() {
            override fun get(key: KotlinType): TypeProjection? {
                return typeVariableSubstitutors.values.reversed().asSequence().mapNotNull { it.substitution.get(key) }.firstOrNull()
            }
        })
    }

    fun addConstraintForValueArgument(
            valueArgument: ValueArgument,
            valueParameterDescriptor: ValueParameterDescriptor,
            substitutor: TypeSubstitutor,
            builder: ConstraintSystem.Builder,
            context: CallCandidateResolutionContext<*>,
            resolveFunctionArgumentBodies: ResolveArgumentsMode
    ) {
        val effectiveExpectedType = getEffectiveExpectedType(valueParameterDescriptor, valueArgument)
        val argumentExpression = valueArgument.getArgumentExpression()

        val expectedType = substitutor.substitute(effectiveExpectedType, Variance.INVARIANT)
        val dataFlowInfoForArgument = context.candidateCall.dataFlowInfoForArguments.getInfo(valueArgument)
        val newContext = context.replaceExpectedType(expectedType).replaceDataFlowInfo(dataFlowInfoForArgument)

        val typeInfoForCall = argumentTypeResolver.getArgumentTypeInfo(argumentExpression, newContext, resolveFunctionArgumentBodies)
        context.candidateCall.dataFlowInfoForArguments.updateInfo(valueArgument, typeInfoForCall.dataFlowInfo)

        val constraintPosition = VALUE_PARAMETER_POSITION.position(valueParameterDescriptor.index)

        if (addConstraintForNestedCall(argumentExpression, constraintPosition, builder, newContext, effectiveExpectedType)) return

        val type = updateResultTypeForSmartCasts(typeInfoForCall.type, argumentExpression, context.replaceDataFlowInfo(dataFlowInfoForArgument))
        builder.addSubtypeConstraint(
                type,
                builder.compositeSubstitutor().substitute(effectiveExpectedType, Variance.INVARIANT),
                constraintPosition
        )
    }

    private fun addConstraintForNestedCall(
            argumentExpression: KtExpression?,
            constraintPosition: ConstraintPosition,
            builder: ConstraintSystem.Builder,
            context: CallCandidateResolutionContext<*>,
            effectiveExpectedType: KotlinType
    ): Boolean {
        val resolutionResults = getResolutionResultsCachedData(argumentExpression, context)?.resolutionResults
        if (resolutionResults == null || !resolutionResults.isSingleResult) return false

        val nestedCall = resolutionResults.resultingCall
        if (nestedCall.isCompleted) return false

        val nestedConstraintSystem = nestedCall.constraintSystem ?: return false

        val candidateDescriptor = nestedCall.candidateDescriptor
        val returnType = candidateDescriptor.returnType ?: return false

        val nestedTypeVariables = nestedConstraintSystem.getNestedTypeVariables(returnType)

        // we add an additional type variable only if no information is inferred for it.
        // otherwise we add currently inferred return type as before
        if (nestedTypeVariables.any { nestedConstraintSystem.getTypeBounds(it).bounds.isNotEmpty() }) return false

        val candidateWithFreshVariables = FunctionDescriptorUtil.alphaConvertTypeParameters(candidateDescriptor)
        val conversion = candidateDescriptor.typeParameters.zip(candidateWithFreshVariables.typeParameters).toMap()

        val freshVariables = returnType.getNestedTypeParameters().mapNotNull { conversion[it] }
        builder.registerTypeVariables(nestedCall.call.toHandle(), freshVariables, external = true)
        // Safe call result must be nullable if receiver is nullable
        val argumentExpressionType = nestedCall.makeNullableTypeIfSafeReceiver(candidateWithFreshVariables.returnType, context)

        builder.addSubtypeConstraint(
                argumentExpressionType,
                builder.compositeSubstitutor().substitute(effectiveExpectedType, Variance.INVARIANT),
                constraintPosition
        )

        return true
    }

    private fun updateResultTypeForSmartCasts(
            type: KotlinType?,
            argumentExpression: KtExpression?,
            context: ResolutionContext<*>
    ): KotlinType? {
        val deparenthesizedArgument = KtPsiUtil.getLastElementDeparenthesized(argumentExpression, context.statementFilter)
        if (deparenthesizedArgument == null || type == null) return type

        val dataFlowValue = DataFlowValueFactory.createDataFlowValue(deparenthesizedArgument, type, context)
        if (!dataFlowValue.isStable) return type

        val possibleTypes = context.dataFlowInfo.getCollectedTypes(dataFlowValue)
        if (possibleTypes.isEmpty()) return type

        return TypeIntersector.intersectTypes(KotlinTypeChecker.DEFAULT, possibleTypes + type)
    }

    fun <D : CallableDescriptor> completeTypeInferenceDependentOnFunctionArgumentsForCall(context: CallCandidateResolutionContext<D>) {
        val resolvedCall = context.candidateCall
        val constraintSystem = resolvedCall.constraintSystem?.toBuilder() ?: return

        // constraints for function literals
        // Value parameters
        for ((valueParameterDescriptor, resolvedValueArgument) in resolvedCall.valueArguments) {
            for (valueArgument in resolvedValueArgument.arguments) {
                valueArgument.getArgumentExpression()?.let { argumentExpression ->
                    ArgumentTypeResolver.getFunctionLiteralArgumentIfAny(argumentExpression, context)?.let { functionLiteral ->
                        addConstraintForFunctionLiteralArgument(functionLiteral, valueArgument, valueParameterDescriptor, constraintSystem, context,
                                                                resolvedCall.candidateDescriptor.returnType)
                    }
                    ArgumentTypeResolver.getCallableReferenceExpressionIfAny(argumentExpression, context)?.let { callableReference ->
                        addConstraintForCallableReference(callableReference, valueArgument, valueParameterDescriptor, constraintSystem, context)
                    }
                }
            }
        }
        val resultingSystem = constraintSystem.build()
        resolvedCall.setConstraintSystem(resultingSystem)
        resolvedCall.setResultingSubstitutor(resultingSystem.resultingSubstitutor)
    }

    // See KT-5385
    // When literal returns T, and it's an argument of a function that also returns T,
    // and we have some expected type Type, we can expected from literal to return Type
    // Otherwise we do not care about literal's exact return type
    private fun estimateLiteralReturnType(
            context: CallCandidateResolutionContext<*>,
            literalExpectedType: KotlinType,
            ownerReturnType: KotlinType?
    ) = if (!TypeUtils.noExpectedType(context.expectedType) &&
            ownerReturnType != null &&
            TypeUtils.isTypeParameter(ownerReturnType) &&
            literalExpectedType.isFunctionTypeOrSubtype &&
            getReturnTypeForCallable(literalExpectedType) == ownerReturnType)
        context.expectedType
    else DONT_CARE

    private fun <D : CallableDescriptor> addConstraintForFunctionLiteralArgument(
            functionLiteral: KtFunction,
            valueArgument: ValueArgument,
            valueParameterDescriptor: ValueParameterDescriptor,
            constraintSystem: ConstraintSystem.Builder,
            context: CallCandidateResolutionContext<D>,
            argumentOwnerReturnType: KotlinType?
    ) {
        val argumentExpression = valueArgument.getArgumentExpression() ?: return

        val effectiveExpectedType = getEffectiveExpectedType(valueParameterDescriptor, valueArgument)
        var expectedType = constraintSystem.build().currentSubstitutor.substitute(effectiveExpectedType, Variance.INVARIANT)
        if (expectedType == null || TypeUtils.isDontCarePlaceholder(expectedType)) {
            expectedType = argumentTypeResolver.getShapeTypeOfFunctionLiteral(functionLiteral, context.scope, context.trace, false)
        }
        if (expectedType == null || !expectedType.isFunctionTypeOrSubtype || hasUnknownFunctionParameter(expectedType)) {
            return
        }
        val dataFlowInfoForArguments = context.candidateCall.dataFlowInfoForArguments
        val dataFlowInfoForArgument = dataFlowInfoForArguments.getInfo(valueArgument)

        val effectiveExpectedTypeInSystem =
                constraintSystem.typeVariableSubstitutors[context.call.toHandle()]?.substitute(effectiveExpectedType, Variance.INVARIANT)

        //todo analyze function literal body once in 'dependent' mode, then complete it with respect to expected type
        val hasExpectedReturnType = !hasUnknownReturnType(expectedType)
        val position = VALUE_PARAMETER_POSITION.position(valueParameterDescriptor.index)
        if (hasExpectedReturnType) {
            val temporaryToResolveFunctionLiteral = TemporaryTraceAndCache.create(
                    context, "trace to resolve function literal with expected return type", argumentExpression)

            val statementExpression = KtPsiUtil.getExpressionOrLastStatementInBlock(functionLiteral.bodyExpression) ?: return
            val mismatch = BooleanArray(1)
            val errorInterceptingTrace = ExpressionTypingUtils.makeTraceInterceptingTypeMismatch(
                    temporaryToResolveFunctionLiteral.trace, statementExpression, mismatch)
            val newContext = context.replaceBindingTrace(errorInterceptingTrace).replaceExpectedType(expectedType)
                    .replaceDataFlowInfo(dataFlowInfoForArgument).replaceResolutionResultsCache(temporaryToResolveFunctionLiteral.cache)
                    .replaceContextDependency(INDEPENDENT)
            val type = argumentTypeResolver.getFunctionLiteralTypeInfo(
                    argumentExpression, functionLiteral, newContext, RESOLVE_FUNCTION_ARGUMENTS).type
            if (!mismatch[0]) {
                constraintSystem.addSubtypeConstraint(type, effectiveExpectedTypeInSystem, position)
                temporaryToResolveFunctionLiteral.commit()
                return
            }
        }
        val estimatedReturnType = estimateLiteralReturnType(context, effectiveExpectedType, argumentOwnerReturnType)
        val expectedTypeWithEstimatedReturnType = replaceReturnTypeForCallable(expectedType, estimatedReturnType)
        val newContext = context.replaceExpectedType(expectedTypeWithEstimatedReturnType).replaceDataFlowInfo(dataFlowInfoForArgument)
                .replaceContextDependency(INDEPENDENT)
        val type = argumentTypeResolver.getFunctionLiteralTypeInfo(argumentExpression, functionLiteral, newContext, RESOLVE_FUNCTION_ARGUMENTS).type
        constraintSystem.addSubtypeConstraint(type, effectiveExpectedTypeInSystem, position)
    }

    private fun <D : CallableDescriptor> addConstraintForCallableReference(
            callableReference: KtCallableReferenceExpression,
            valueArgument: ValueArgument,
            valueParameterDescriptor: ValueParameterDescriptor,
            constraintSystem: ConstraintSystem.Builder,
            context: CallCandidateResolutionContext<D>
    ) {
        val effectiveExpectedType = getEffectiveExpectedType(valueParameterDescriptor, valueArgument)
        val expectedType = getExpectedTypeForCallableReference(callableReference, constraintSystem, context, effectiveExpectedType)
                           ?: return
        if (!ReflectionTypes.isCallableType(expectedType)) return
        val resolvedType = getResolvedTypeForCallableReference(callableReference, context, expectedType, valueArgument)
        val position = VALUE_PARAMETER_POSITION.position(valueParameterDescriptor.index)
        constraintSystem.addSubtypeConstraint(
                resolvedType,
                constraintSystem.typeVariableSubstitutors[context.call.toHandle()]?.substitute(effectiveExpectedType, Variance.INVARIANT),
                position
        )
    }

    private fun <D : CallableDescriptor> getExpectedTypeForCallableReference(
            callableReference: KtCallableReferenceExpression,
            constraintSystem: ConstraintSystem.Builder,
            context: CallCandidateResolutionContext<D>,
            effectiveExpectedType: KotlinType
    ): KotlinType? {
        val substitutedType = constraintSystem.build().currentSubstitutor.substitute(effectiveExpectedType, Variance.INVARIANT)
        if (substitutedType != null && !TypeUtils.isDontCarePlaceholder(substitutedType))
            return substitutedType

        val shapeType = argumentTypeResolver.getShapeTypeOfCallableReference(callableReference, context, false)
        if (shapeType != null && shapeType.isFunctionTypeOrSubtype && !hasUnknownFunctionParameter(shapeType))
            return shapeType

        return null
    }

    private fun <D : CallableDescriptor> getResolvedTypeForCallableReference(
            callableReference: KtCallableReferenceExpression,
            context: CallCandidateResolutionContext<D>,
            expectedType: KotlinType,
            valueArgument: ValueArgument
    ): KotlinType? {
        val dataFlowInfoForArgument = context.candidateCall.dataFlowInfoForArguments.getInfo(valueArgument)
        val expectedTypeWithoutReturnType = if (!hasUnknownReturnType(expectedType)) replaceReturnTypeByUnknown(expectedType) else expectedType
        val newContext = context
                .replaceExpectedType(expectedTypeWithoutReturnType)
                .replaceDataFlowInfo(dataFlowInfoForArgument)
                .replaceContextDependency(INDEPENDENT)
        val argumentExpression = valueArgument.getArgumentExpression()!!
        val type = argumentTypeResolver.getCallableReferenceTypeInfo(
                argumentExpression, callableReference, newContext, RESOLVE_FUNCTION_ARGUMENTS).type
        return type
    }
}

fun getResolutionResultsCachedData(expression: KtExpression?, context: ResolutionContext<*>): ResolutionResultsCache.CachedData? {
    if (!ExpressionTypingUtils.dependsOnExpectedType(expression)) return null
    val argumentCall = expression?.getCall(context.trace.bindingContext) ?: return null

    return context.resolutionResultsCache[argumentCall]
}

fun makeConstantSubstitutor(typeParameterDescriptors: Collection<TypeParameterDescriptor>, type: KotlinType): TypeSubstitutor {
    val constructors = typeParameterDescriptors.map { it.typeConstructor }.toSet()
    val projection = TypeProjectionImpl(type)

    return TypeSubstitutor.create(object : TypeConstructorSubstitution() {
        override operator fun get(key: TypeConstructor) =
                if (key in constructors) projection else null

        override fun isEmpty() = false
    })
}
