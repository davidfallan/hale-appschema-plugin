/*
 * Copyright (c) 2015 Data Harmonisation Panel
 * 
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     Data Harmonisation Panel <http://www.dhpanel.eu>
 */

package it.geosolutions.hale.io.appschema.writer.internal;

import static eu.esdihumboldt.hale.common.align.model.functions.JoinFunction.PARAMETER_JOIN;
import static it.geosolutions.hale.io.appschema.AppSchemaIO.isReferenceType;
import static it.geosolutions.hale.io.appschema.writer.AppSchemaMappingUtils.findOwningType;
import static it.geosolutions.hale.io.appschema.writer.AppSchemaMappingUtils.findOwningTypePath;
import static it.geosolutions.hale.io.appschema.writer.AppSchemaMappingUtils.getSortedJoinConditions;
//import static it.geosolutions.hale.io.appschema.writer.internal.AppSchemaMappingUtils.findChildFeatureType;
import static it.geosolutions.hale.io.appschema.writer.AppSchemaMappingUtils.getTargetProperty;
import static it.geosolutions.hale.io.appschema.writer.AppSchemaMappingUtils.getTargetType;
import static it.geosolutions.hale.io.appschema.writer.AppSchemaMappingUtils.isHRefAttribute;

import java.util.Collection;
import java.util.List;

import de.fhg.igd.slf4jplus.ALogger;
import de.fhg.igd.slf4jplus.ALoggerFactory;
import eu.esdihumboldt.cst.functions.core.Join;
import eu.esdihumboldt.hale.common.align.model.Alignment;
import eu.esdihumboldt.hale.common.align.model.AlignmentUtil;
import eu.esdihumboldt.hale.common.align.model.Cell;
import eu.esdihumboldt.hale.common.align.model.ChildContext;
import eu.esdihumboldt.hale.common.align.model.EntityDefinition;
import eu.esdihumboldt.hale.common.align.model.Property;
import eu.esdihumboldt.hale.common.align.model.functions.join.JoinParameter;
import eu.esdihumboldt.hale.common.align.model.functions.join.JoinParameter.JoinCondition;
import eu.esdihumboldt.hale.common.align.model.impl.PropertyEntityDefinition;
import eu.esdihumboldt.hale.common.align.model.impl.TypeEntityDefinition;
import eu.esdihumboldt.hale.common.schema.model.PropertyDefinition;
import eu.esdihumboldt.hale.common.schema.model.TypeDefinition;
import it.geosolutions.hale.io.appschema.AppSchemaIO;
import it.geosolutions.hale.io.appschema.impl.internal.generated.app_schema.AttributeExpressionMappingType;
import it.geosolutions.hale.io.appschema.impl.internal.generated.app_schema.AttributeMappingType;
import it.geosolutions.hale.io.appschema.impl.internal.generated.app_schema.TypeMappingsPropertyType.FeatureTypeMapping;
import it.geosolutions.hale.io.appschema.model.ChainConfiguration;
import it.geosolutions.hale.io.appschema.model.FeatureChaining;
import it.geosolutions.hale.io.appschema.writer.AppSchemaMappingUtils;
import it.geosolutions.hale.io.appschema.writer.internal.mapping.AppSchemaMappingContext;
import it.geosolutions.hale.io.appschema.writer.internal.mapping.MappingWrapper;

/**
 * Translates a type cell specifying a {@link Join} transformation function to
 * two app-schema feature type mappings.
 * 
 * <p>
 * The current implementation has some limitations:
 * <ul>
 * <li>joining more than two tables is not supported</li>
 * <li>specifying more than one join condition is not supported</li>
 * <li>the first join type is considered to be the container feature type; the
 * second join type is considered to be the nested feature type</li>
 * <li>joining non-feature types is not supported</li>
 * </ul>
 * </p>
 * 
 * @author Stefano Costa, GeoSolutions
 */
public class JoinHandler implements TypeTransformationHandler {

	private static final ALogger LOG = ALoggerFactory.getLogger(JoinHandler.class);

	private PropertyEntityDefinition baseProperty;
	private PropertyEntityDefinition joinProperty;

	/**
	 * @see it.geosolutions.hale.io.appschema.writer.internal.TypeTransformationHandler#handleTypeTransformation(eu.esdihumboldt.hale.common.align.model.Cell,
	 *      it.geosolutions.hale.io.appschema.writer.internal.mapping.AppSchemaMappingContext)
	 */
	@Override
	public FeatureTypeMapping handleTypeTransformation(Cell typeCell,
			AppSchemaMappingContext context) {
		MappingWrapper mapping = context.getMappingWrapper();
		Alignment alignment = context.getAlignment();
		FeatureChaining featureChaining = context.getFeatureChaining();

		final JoinParameter joinParameter = typeCell.getTransformationParameters()
				.get(PARAMETER_JOIN).get(0).as(JoinParameter.class);

		String validation = joinParameter.validate();
		if (validation != null)
			throw new IllegalArgumentException("Join parameter invalid: " + validation);

		// check only single predicate conditions have been used
		int[] conditionCount = new int[joinParameter.getTypes().size()];
		List<JoinCondition> joinConditions = getSortedJoinConditions(joinParameter);
		for (JoinCondition joinCondition : joinConditions) {
			TypeEntityDefinition joinType = AlignmentUtil.getTypeEntity(joinCondition.joinProperty);
			int typeIdx = joinParameter.getTypes().indexOf(joinType);
			conditionCount[typeIdx]++;
			if (conditionCount[typeIdx] > 1) {
				throw new IllegalArgumentException(
						"Only single condition joins are supported so far");
			}
		}

		FeatureTypeMapping topMostMapping = null;
		for (int chainIdx = 0; chainIdx < joinParameter.getTypes().size() - 1; chainIdx++) {
			ChainConfiguration previousChainConf = null;
			ChainConfiguration chainConf = null;
			if (featureChaining != null) {
				chainConf = featureChaining.getChain(typeCell.getId(), chainIdx);
				if (chainConf != null) {
					// detect unbounded anonymous sequence or element
					if (isUnboundedAnonymousSequenceOrElement(chainConf)) {
						// nothing to do here
						return null;
					}
				}
				if (chainConf != null && chainConf.getPrevChainIndex() >= 0) {
					previousChainConf = featureChaining.getChain(typeCell.getId(),
							chainConf.getPrevChainIndex());
				}
			}

			// join is done pair-wise: I assume the first type is the container
			// type, whereas the second type is nested in the first
			JoinCondition joinCondition = joinConditions.get(chainIdx);
			baseProperty = joinCondition.baseProperty;
			joinProperty = joinCondition.joinProperty;
			TypeEntityDefinition containerType = AlignmentUtil.getTypeEntity(baseProperty);
			TypeEntityDefinition nestedType = AlignmentUtil.getTypeEntity(joinProperty);

			EntityDefinition containerTypeTarget = null;
			TypeDefinition containerTypeTargetType = null;
			String containerTypeTargetMappingName = null;
			if (previousChainConf == null) {
				containerTypeTarget = getTargetType(typeCell).getDefinition();
				containerTypeTargetType = containerTypeTarget.getType();
			}
			else {
				containerTypeTarget = previousChainConf.getNestedTypeTarget();
				containerTypeTargetType = previousChainConf.getNestedTypeTarget().getDefinition()
						.getPropertyType();
				containerTypeTargetMappingName = previousChainConf.getMappingName();
			}

			String containerMappingName = null;
			if (previousChainConf != null) {
				containerMappingName = previousChainConf.getMappingName();
			}
			FeatureTypeMapping containerFTMapping = context
					.getOrCreateFeatureTypeMapping(containerTypeTargetType, containerMappingName);
			containerFTMapping
					.setSourceType(containerType.getDefinition().getName().getLocalPart());

			// build FeatureTypeMapping for nested type
			TypeDefinition nestedFT = null;
			List<ChildContext> nestedFTPath = null;
			FeatureTypeMapping nestedFTMapping = null;

			if (chainConf != null) {
				nestedFT = chainConf.getNestedTypeTarget().getDefinition().getPropertyType();
				nestedFTPath = chainConf.getNestedTypeTarget().getPropertyPath();
				// remove last element (only if is is not a ReferenceType type)
				if (!isReferenceType(nestedFT)) {
					nestedFTPath = nestedFTPath.subList(0, nestedFTPath.size() - 1);
				}
				// if exists a linked type for reference case, assign it
				if (chainConf.getReferenceLinkedType() != null)
					nestedFT = chainConf.getReferenceLinkedType();
				nestedFTMapping = context.getOrCreateFeatureTypeMapping(nestedFT,
						chainConf.getMappingName());
				nestedFTMapping.setSourceType(nestedType.getDefinition().getName().getLocalPart());
			}
			// chainConf == null
			else {
				if (joinParameter.getTypes().size() > 2) {
					throw new IllegalArgumentException(
							"If no feature chaining configuration is provided, only join between 2 types is supported");
				}

				// do your best to figure it out on your own... good luck!
				Collection<? extends Cell> propertyCells = alignment.getPropertyCells(typeCell);
				for (Cell propertyCell : propertyCells) {
					Property sourceProperty = AppSchemaMappingUtils.getSourceProperty(propertyCell);
					if (sourceProperty != null) {
						TypeDefinition sourceType = sourceProperty.getDefinition().getDefinition()
								.getParentType();
						if (sourceType.getName().equals(nestedType.getDefinition().getName())) {
							// source property belongs to nested type: determine
							// target type
							Property targetProperty = getTargetProperty(propertyCell);
							// nestedFT =
							// findOwningFeatureType(targetProperty.getDefinition());
							nestedFT = findOwningType(targetProperty.getDefinition(),
									context.getRelevantTargetTypes());
							if (nestedFT != null && !nestedFT.getName()
									.equals(containerTypeTargetType.getName())) {
								// target property belongs to a feature type
								// different from the already mapped one: build
								// a new mapping
								nestedFTPath = findOwningTypePath(targetProperty.getDefinition(),
										context.getRelevantTargetTypes());
								nestedFTMapping = context.getOrCreateFeatureTypeMapping(nestedFT);
								nestedFTMapping.setSourceType(
										nestedType.getDefinition().getName().getLocalPart());

								// I assume at most 2 FeatureTypes are involved
								// in the join
								break;
							}
							else if (isHRefAttribute(
									targetProperty.getDefinition().getDefinition())) {
								// check if target property is a href attribute
								Property hrefProperty = targetProperty;
								List<ChildContext> hrefPropertyPath = hrefProperty.getDefinition()
										.getPropertyPath();
								List<ChildContext> hrefContainerPath = hrefPropertyPath.subList(0,
										hrefPropertyPath.size() - 1);
								TypeDefinition hrefParentType = hrefProperty.getDefinition()
										.getDefinition().getParentType();
								// TypeDefinition childFT =
								// findChildFeatureType(hrefParentType);
								TypeDefinition childFT = AppSchemaMappingUtils.findChildType(
										hrefParentType, context.getRelevantTargetTypes());

								if (childFT != null) {
									nestedFTPath = hrefContainerPath;
									nestedFTMapping = context
											.getOrCreateFeatureTypeMapping(childFT);
									nestedFTMapping.setSourceType(
											nestedType.getDefinition().getName().getLocalPart());

									// I assume at most 2 FeatureTypes are
									// involved in the join
									break;
								}
							}
						}
					}
				}
			}

			// build join mapping
			if (nestedFTMapping != null && nestedFTPath != null) {
				AttributeMappingType containerJoinMapping = context.getOrCreateAttributeMapping(
						containerTypeTargetType, containerTypeTargetMappingName, nestedFTPath);
				// no real join available, get out
				if (containerJoinMapping == null)
					return containerFTMapping;
				containerJoinMapping.setTargetAttribute(
						mapping.buildAttributeXPath(containerTypeTargetType, nestedFTPath));
				// set isMultiple attribute
				PropertyDefinition targetPropertyDef = nestedFTPath.get(nestedFTPath.size() - 1)
						.getChild().asProperty();
				if (AppSchemaMappingUtils.isMultiple(targetPropertyDef)) {
					containerJoinMapping.setIsMultiple(true);
				}

				AttributeExpressionMappingType containerSourceExpr = new AttributeExpressionMappingType();
				// join column extracted from join condition
				containerSourceExpr.setOCQL(baseProperty.getDefinition().getName().getLocalPart());
				containerSourceExpr.setLinkElement(getLinkElementValue(nestedFTMapping));
				String linkField = context.getUniqueFeatureLinkAttribute(nestedFT,
						nestedFTMapping.getMappingName());
				containerSourceExpr.setLinkField(linkField);
				containerJoinMapping.setSourceExpression(containerSourceExpr);

				AttributeMappingType nestedJoinMapping = new AttributeMappingType();
				AttributeExpressionMappingType nestedSourceExpr = new AttributeExpressionMappingType();
				// join column extracted from join condition
				nestedSourceExpr.setOCQL(joinProperty.getDefinition().getName().getLocalPart());
				nestedJoinMapping.setSourceExpression(nestedSourceExpr);
				nestedJoinMapping.setTargetAttribute(linkField);
				nestedFTMapping.getAttributeMappings().getAttributeMapping().add(nestedJoinMapping);
			}

			if (chainIdx == 0) {
				topMostMapping = containerFTMapping;
			}
		}

		return topMostMapping;
	}

	private boolean isUnboundedAnonymousSequenceOrElement(ChainConfiguration chainConf) {
		try {
			TypeDefinition nestedTargetType = chainConf.getNestedTypeTarget().getDefinition()
					.getPropertyType();
			// not ReferenceType allowed here
			if (AppSchemaIO.isReferenceType(nestedTargetType)) {
				return false;
			}
			return AppSchemaIO.isUnboundedSequence(nestedTargetType)
					|| AppSchemaIO.isUnboundedElement(chainConf.getNestedTypeTarget());
		} catch (NullPointerException ex) {
			LOG.warn("Exception trying to determine if it is an unbounded sequence", ex);
			// this should never happen
		}
		return false;
	}

	private String getLinkElementValue(FeatureTypeMapping nestedFeatureTypeMapping) {
		if (nestedFeatureTypeMapping.getMappingName() != null
				&& !nestedFeatureTypeMapping.getMappingName().isEmpty()) {
			// playing safe: always enclose mapping name in single quotes
			return "'" + nestedFeatureTypeMapping.getMappingName() + "'";
		}
		else {
			return nestedFeatureTypeMapping.getTargetElement();
		}
	}

}
