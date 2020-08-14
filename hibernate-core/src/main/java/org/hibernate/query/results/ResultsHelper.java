/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.results;

import org.hibernate.NotYetImplementedFor6Exception;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.BasicValuedModelPart;
import org.hibernate.metamodel.mapping.EmbeddableValuedModelPart;
import org.hibernate.metamodel.mapping.EntityIdentifierMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.internal.SingleAttributeIdentifierMapping;
import org.hibernate.query.EntityIdentifierNavigablePath;
import org.hibernate.query.NavigablePath;
import org.hibernate.query.internal.ImplicitAttributeFetchMemento;
import org.hibernate.query.internal.ImplicitModelPartResultMemento;
import org.hibernate.query.internal.ResultSetMappingResolutionContext;
import org.hibernate.query.named.FetchMemento;
import org.hibernate.query.named.ModelPartResultMemento;
import org.hibernate.query.named.ResultMemento;
import org.hibernate.query.results.implicit.ImplicitFetchBuilder;
import org.hibernate.query.results.implicit.ImplicitFetchBuilderBasic;
import org.hibernate.query.results.implicit.ImplicitFetchBuilderEmbeddable;
import org.hibernate.query.results.implicit.ImplicitModelPartResultBuilderEntity;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.results.graph.DomainResult;
import org.hibernate.sql.results.graph.DomainResultCreationState;
import org.hibernate.sql.results.graph.Fetch;
import org.hibernate.sql.results.graph.FetchParent;
import org.hibernate.sql.results.graph.Fetchable;
import org.hibernate.sql.results.graph.basic.BasicFetch;
import org.hibernate.sql.results.graph.embeddable.EmbeddableValuedFetchable;
import org.hibernate.sql.results.graph.entity.EntityValuedFetchable;

/**
 * @author Steve Ebersole
 */
public class ResultsHelper {
	public static int jdbcPositionToValuesArrayPosition(int jdbcPosition) {
		return jdbcPosition - 1;
	}

	public static int valuesArrayPositionToJdbcPosition(int valuesArrayPosition) {
		return valuesArrayPosition + 1;
	}

	public static DomainResultCreationStateImpl impl(DomainResultCreationState creationState) {
		return unwrap( creationState );
	}

	private static DomainResultCreationStateImpl unwrap(DomainResultCreationState creationState) {
		if ( creationState instanceof DomainResultCreationStateImpl ) {
			return ( (DomainResultCreationStateImpl) creationState );
		}

		throw new IllegalArgumentException(
				"Passed DomainResultCreationState not an instance of org.hibernate.query.results.DomainResultCreationStateImpl"
		);
	}

	private ResultsHelper() {
	}

	public static boolean isIdentifier(EntityIdentifierMapping identifierDescriptor, String... names) {
		final String identifierAttributeName = identifierDescriptor instanceof SingleAttributeIdentifierMapping
				? ( (SingleAttributeIdentifierMapping) identifierDescriptor ).getAttributeName()
				: EntityIdentifierMapping.ROLE_LOCAL_NAME;

		for ( int i = 0; i < names.length; i++ ) {
			final String name = names[ i ];
			if ( EntityIdentifierMapping.ROLE_LOCAL_NAME.equals( name ) ) {
				return true;
			}

			if ( identifierAttributeName.equals( name ) ) {
				return true;
			}
		}

		return false;
	}

//	public static ResultMemento implicitIdentifierResult(
//			EntityIdentifierMapping identifierMapping,
//			EntityIdentifierNavigablePath idPath,
//			ResultSetMappingResolutionContext resolutionContext) {
//		return new ImplicitModelPartResultMemento( idPath, identifierMapping );
//	}

	public static DomainResult implicitIdentifierResult(
			EntityIdentifierMapping identifierMapping,
			EntityIdentifierNavigablePath idPath,
			DomainResultCreationState creationState) {
		final DomainResultCreationStateImpl creationStateImpl = impl( creationState );
		final TableGroup tableGroup = creationStateImpl.getFromClauseAccess().getTableGroup( idPath.getParent() );

		return identifierMapping.createDomainResult(
				idPath,
				tableGroup,
				null,
				creationState
		);
	}

	public static ModelPartResultMemento implicitEntityResult(
			String entityName,
			ResultSetMappingResolutionContext resolutionContext) {
		final SessionFactoryImplementor sessionFactory = resolutionContext.getSessionFactory();
		final EntityMappingType entityMappingType = resolutionContext.getSessionFactory()
				.getRuntimeMetamodels()
				.getEntityMappingType( entityName );
		return new ImplicitModelPartResultMemento( new NavigablePath( entityName ), entityMappingType );
	}

	public static FetchMemento implicitFetch(
			AttributeMapping attributeMapping,
			NavigablePath attributePath,
			ResultSetMappingResolutionContext resolutionContext) {
		return new ImplicitAttributeFetchMemento( attributePath, attributeMapping );
	}

	public static ResultBuilder implicitEntityResultBuilder(
			Class<?> resultMappingClass,
			ResultSetMappingResolutionContext resolutionContext) {
		final EntityMappingType entityMappingType = resolutionContext
				.getSessionFactory()
				.getRuntimeMetamodels()
				.getEntityMappingType( resultMappingClass );
		return new ImplicitModelPartResultBuilderEntity( entityMappingType );
	}

	public static ImplicitFetchBuilder implicitFetchBuilder(NavigablePath fetchPath, Fetchable fetchable) {
		if ( fetchable instanceof BasicValuedModelPart ) {
			final BasicValuedModelPart basicValuedFetchable = (BasicValuedModelPart) fetchable;
			return new ImplicitFetchBuilderBasic( fetchPath, basicValuedFetchable );
		}

		if ( fetchable instanceof EmbeddableValuedFetchable ) {
			final EmbeddableValuedFetchable embeddableValuedFetchable = (EmbeddableValuedFetchable) fetchable;
			return new ImplicitFetchBuilderEmbeddable( fetchPath, embeddableValuedFetchable );
		}

		if ( fetchable instanceof EntityValuedFetchable ) {
			final EntityValuedFetchable entityValuedFetchable = (EntityValuedFetchable) fetchable;
			throw new NotYetImplementedFor6Exception( "Support for implicit entity-valued fetches is not yet implemented" );
		}

		throw new UnsupportedOperationException();
	}

	public static String attributeName(EntityIdentifierMapping identifierMapping) {
		return identifierMapping instanceof SingleAttributeIdentifierMapping
				? ( (SingleAttributeIdentifierMapping) identifierMapping ).getAttributeName()
				: null;
	}

	public static DomainResult convertIdFetchToResult(Fetch fetch, DomainResultCreationState creationState) {
		final EntityIdentifierMapping idMapping = (EntityIdentifierMapping) fetch.getFetchedMapping();
		if ( fetch instanceof BasicFetch ) {
			final BasicFetch<?> basicFetch = (BasicFetch<?>) fetch;

		}
		return null;
	}
}
