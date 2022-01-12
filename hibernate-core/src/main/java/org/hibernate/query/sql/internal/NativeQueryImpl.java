/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sql.internal;

import java.io.Serializable;
import java.time.Instant;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.hibernate.CacheMode;
import org.hibernate.FlushMode;
import org.hibernate.HibernateException;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.MappingException;
import org.hibernate.QueryException;
import org.hibernate.ScrollMode;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.query.spi.NativeQueryInterpreter;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.graph.GraphSemantic;
import org.hibernate.graph.RootGraph;
import org.hibernate.graph.spi.RootGraphImplementor;
import org.hibernate.internal.AbstractSharedSessionContract;
import org.hibernate.internal.util.MathHelper;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.internal.util.collections.CollectionHelper;
import org.hibernate.jpa.internal.util.LockModeTypeHelper;
import org.hibernate.jpa.spi.NativeQueryTupleTransformer;
import org.hibernate.metamodel.model.domain.BasicDomainType;
import org.hibernate.query.BindableType;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.ParameterMetadata;
import org.hibernate.query.Query;
import org.hibernate.query.QueryParameter;
import org.hibernate.query.ResultListTransformer;
import org.hibernate.query.TupleTransformer;
import org.hibernate.query.internal.ParameterMetadataImpl;
import org.hibernate.query.internal.QueryOptionsImpl;
import org.hibernate.query.internal.QueryParameterBindingsImpl;
import org.hibernate.query.internal.ResultSetMappingResolutionContext;
import org.hibernate.query.named.NamedResultSetMappingMemento;
import org.hibernate.query.results.Builders;
import org.hibernate.query.results.ResultBuilder;
import org.hibernate.query.results.ResultSetMapping;
import org.hibernate.query.results.ResultSetMappingImpl;
import org.hibernate.query.results.dynamic.DynamicFetchBuilderLegacy;
import org.hibernate.query.results.dynamic.DynamicResultBuilderEntityStandard;
import org.hibernate.query.results.dynamic.DynamicResultBuilderInstantiation;
import org.hibernate.query.spi.AbstractQuery;
import org.hibernate.query.spi.DomainQueryExecutionContext;
import org.hibernate.query.spi.MutableQueryOptions;
import org.hibernate.query.spi.NonSelectQueryPlan;
import org.hibernate.query.spi.ParameterMetadataImplementor;
import org.hibernate.query.spi.QueryEngine;
import org.hibernate.query.spi.QueryInterpretationCache;
import org.hibernate.query.spi.QueryParameterBinding;
import org.hibernate.query.spi.QueryParameterBindings;
import org.hibernate.query.spi.QueryParameterImplementor;
import org.hibernate.query.spi.ScrollableResultsImplementor;
import org.hibernate.query.spi.SelectQueryPlan;
import org.hibernate.query.sql.spi.NamedNativeQueryMemento;
import org.hibernate.query.sql.spi.NativeQueryImplementor;
import org.hibernate.query.sql.spi.NativeSelectQueryDefinition;
import org.hibernate.query.sql.spi.NativeSelectQueryPlan;
import org.hibernate.query.sql.spi.NonSelectInterpretationsKey;
import org.hibernate.query.sql.spi.ParameterInterpretation;
import org.hibernate.query.sql.spi.ParameterOccurrence;
import org.hibernate.query.sql.spi.SelectInterpretationsKey;
import org.hibernate.sql.exec.internal.CallbackImpl;
import org.hibernate.sql.exec.spi.Callback;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesMappingProducer;
import org.hibernate.transform.ResultTransformer;
import org.hibernate.type.BasicType;
import org.hibernate.type.BasicTypeReference;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Parameter;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TemporalType;
import jakarta.persistence.Tuple;
import jakarta.persistence.metamodel.SingularAttribute;

import static org.hibernate.jpa.QueryHints.HINT_NATIVE_LOCKMODE;

/**
 * @author Steve Ebersole
 */
public class NativeQueryImpl<R>
		extends AbstractQuery<R>
		implements NativeQueryImplementor<R>, DomainQueryExecutionContext, ResultSetMappingResolutionContext {
	private final String sqlString;

	private final ParameterMetadataImplementor parameterMetadata;
	private final List<ParameterOccurrence> parameterOccurrences;
	private final QueryParameterBindings parameterBindings;

	private final ResultSetMappingImpl resultSetMapping;
	private final boolean resultMappingSuppliedToCtor;

	private final QueryOptionsImpl queryOptions = new QueryOptionsImpl();

	private Set<String> querySpaces;
	private Callback callback;

	private Object collectionKey;

	/**
	 * Constructs a NativeQueryImpl given a sql query defined in the mappings.
	 */
	public NativeQueryImpl(
			NamedNativeQueryMemento memento,
			SharedSessionContractImplementor session) {
		this(
				memento,
				() -> {
					if ( memento.getResultMappingName() != null ) {
						return new ResultSetMappingImpl( memento.getResultMappingName() );
					}
					else if ( memento.getResultMappingClass() != null ) {
						return new ResultSetMappingImpl( memento.getResultMappingClass().getName() );
					}

					return new ResultSetMappingImpl( memento.getSqlString() );
				},
				(resultSetMapping, querySpaceConsumer, context ) -> {
					if ( memento.getResultMappingName() != null ) {
						final NamedResultSetMappingMemento resultSetMappingMemento = session.getFactory()
								.getQueryEngine()
								.getNamedObjectRepository()
								.getResultSetMappingMemento( memento.getResultMappingName() );
						if ( resultSetMappingMemento != null ) {
							resultSetMappingMemento.resolve( resultSetMapping, querySpaceConsumer, context );
							return true;
						}
					}

					if ( memento.getResultMappingClass() != null ) {
						resultSetMapping.addResultBuilder(
								Builders.implicitEntityResultBuilder(
										memento.getResultMappingClass(),
										context
								)
						);
						return true;
					}

					return false;
				},
				session
		);
	}

	@FunctionalInterface
	private interface ResultSetMappingHandler {
		boolean resolveResultSetMapping(
				ResultSetMappingImpl resultSetMapping,
				Consumer<String> querySpaceConsumer,
				ResultSetMappingResolutionContext context);
	}

	public NativeQueryImpl(
			NamedNativeQueryMemento memento,
			Supplier<ResultSetMappingImpl> resultSetMappingCreator,
			ResultSetMappingHandler resultSetMappingHandler,
			SharedSessionContractImplementor session) {
		super( session );

		final String mementoSqlString = memento.getSqlString();
		final ParameterInterpretation parameterInterpretation = resolveParameterInterpretation( mementoSqlString, session );

		this.sqlString = parameterInterpretation.getAdjustedSqlString();
		this.parameterMetadata = parameterInterpretation.toParameterMetadata( session );
		this.parameterOccurrences = parameterInterpretation.getOrderedParameterOccurrences();
		this.parameterBindings = QueryParameterBindingsImpl.from(
				parameterMetadata,
				session.getFactory(),
				session.isQueryParametersValidationEnabled()
		);
		this.querySpaces = new HashSet<>();

		this.resultSetMapping = resultSetMappingCreator.get();

		//noinspection UnnecessaryLocalVariable
		final boolean appliedAnyResults = resultSetMappingHandler.resolveResultSetMapping(
				resultSetMapping,
				querySpaces::add,
				this
		);

		this.resultMappingSuppliedToCtor = appliedAnyResults;

		applyOptions( memento );
	}

	/**
	 * Constructs a NativeQueryImpl given a sql query defined in the mappings.
	 */
	public NativeQueryImpl(
			NamedNativeQueryMemento memento,
			Class<R> resultJavaType,
			SharedSessionContractImplementor session) {
		this(
				memento,
				() -> {
					final String mappingIdentifier = resultJavaType != null ? resultJavaType.getName() : null;
					return new ResultSetMappingImpl( mappingIdentifier );
				},
				(resultSetMapping, querySpaceConsumer, context) -> {
					if ( memento.getResultMappingName() != null ) {
						final NamedResultSetMappingMemento resultSetMappingMemento = session.getFactory()
								.getQueryEngine()
								.getNamedObjectRepository()
								.getResultSetMappingMemento( memento.getResultMappingName() );
						if ( resultSetMappingMemento != null ) {
							resultSetMappingMemento.resolve( resultSetMapping, querySpaceConsumer, context );
							return true;
						}
					}

					if ( memento.getResultMappingClass() != null ) {
						resultSetMapping.addResultBuilder(
								Builders.implicitEntityResultBuilder(
										memento.getResultMappingClass(),
										context
								)
						);
						return true;
					}
					if ( resultJavaType != null && resultJavaType != Tuple.class ) {
						// todo (6.0): in 5.x we didn't add implicit result builders and by doing so,
						//  the result type check at the end of the constructor will fail like in 5.x
//						final JpaMetamodel jpaMetamodel = context.getSessionFactory().getJpaMetamodel();
//						if ( jpaMetamodel.findEntityType( resultJavaType ) != null ) {
//							resultSetMapping.addResultBuilder(
//									Builders.implicitEntityResultBuilder( resultJavaType, context )
//							);
//						}
//						else {
//							resultSetMapping.addResultBuilder(
//									Builders.scalar(
//											1,
//											context.getSessionFactory()
//													.getTypeConfiguration()
//													.getBasicTypeForJavaType( resultJavaType )
//									)
//							);
//						}
//
//						return true;
					}

					return false;
				},
				session
		);

		if ( resultJavaType == Tuple.class ) {
			setTupleTransformer( new NativeQueryTupleTransformer() );
		}
		else if ( resultJavaType != null ) {
			switch ( resultSetMapping.getNumberOfResultBuilders() ) {
				case 0:
					throw new IllegalArgumentException( "Named query exists but its result type is not compatible" );
				case 1:
					final Class<?> actualResultJavaType = resultSetMapping.getResultBuilders().get( 0 ).getJavaType();
					if ( !resultJavaType.isAssignableFrom( actualResultJavaType ) ) {
						throw buildIncompatibleException( resultJavaType, actualResultJavaType );
					}
					break;
				default:
					throw new IllegalArgumentException( "Cannot create TypedQuery for query with more than one return" );
			}
		}
	}

	/**
	 * Constructs a NativeQueryImpl given a sql query defined in the mappings.
	 */
	public NativeQueryImpl(
			NamedNativeQueryMemento memento,
			String resultSetMappingName,
			SharedSessionContractImplementor session) {
		this(
				memento,
				() -> new ResultSetMappingImpl( resultSetMappingName ),
				(resultSetMapping, querySpaceConsumer, context) -> {
					final NamedResultSetMappingMemento mappingMemento = session.getFactory()
							.getQueryEngine()
							.getNamedObjectRepository()
							.getResultSetMappingMemento( resultSetMappingName );
					assert mappingMemento != null;
					mappingMemento.resolve( resultSetMapping, querySpaceConsumer, context );
					return true;
				},
				session
		);

	}

	public NativeQueryImpl(
			String sqlString,
			NamedResultSetMappingMemento resultSetMappingMemento,
			AbstractSharedSessionContract session) {
		super( session );

		final ParameterInterpretation parameterInterpretation = resolveParameterInterpretation( sqlString, session );

		this.sqlString = parameterInterpretation.getAdjustedSqlString();
		this.parameterMetadata = parameterInterpretation.toParameterMetadata( session );
		this.parameterOccurrences = parameterInterpretation.getOrderedParameterOccurrences();
		this.parameterBindings = QueryParameterBindingsImpl.from(
				parameterMetadata,
				session.getFactory(),
				session.isQueryParametersValidationEnabled()
		);
		this.querySpaces = new HashSet<>();

		this.resultSetMapping = new ResultSetMappingImpl( resultSetMappingMemento.getName() );
		resultSetMappingMemento.resolve(
				resultSetMapping,
				this::addSynchronizedQuerySpace,
				this
		);

		this.resultMappingSuppliedToCtor = true;
	}

	private ParameterInterpretation resolveParameterInterpretation(
			String sqlString,
			SharedSessionContractImplementor session) {
		final SessionFactoryImplementor sessionFactory = session.getFactory();
		final QueryEngine queryEngine = sessionFactory.getQueryEngine();
		final QueryInterpretationCache interpretationCache = queryEngine.getInterpretationCache();

		return interpretationCache.resolveNativeQueryParameters(
					sqlString,
					s -> {
						final ParameterRecognizerImpl parameterRecognizer = new ParameterRecognizerImpl( session.getFactory() );

						session.getFactory().getServiceRegistry()
								.getService( NativeQueryInterpreter.class )
								.recognizeParameters( sqlString, parameterRecognizer );

						return new ParameterInterpretationImpl( parameterRecognizer );
					}
			);
	}

	protected void applyOptions(NamedNativeQueryMemento memento) {
		super.applyOptions( memento );

		final Set<String> copy = CollectionHelper.makeCopy( memento.getQuerySpaces() );
		if ( copy != null ) {
			this.querySpaces = copy;
		}

		// todo (6.0) : query returns
	}

	public NativeQueryImpl(String sqlString, SharedSessionContractImplementor session) {
		super( session );

		this.querySpaces = new HashSet<>();

		final ParameterInterpretation parameterInterpretation = resolveParameterInterpretation( sqlString, session );

		this.sqlString = parameterInterpretation.getAdjustedSqlString();
		this.parameterMetadata = parameterInterpretation.toParameterMetadata( session );
		this.parameterOccurrences = parameterInterpretation.getOrderedParameterOccurrences();
		this.parameterBindings = QueryParameterBindingsImpl.from(
				parameterMetadata,
				session.getFactory(),
				session.isQueryParametersValidationEnabled()
		);

		this.resultSetMapping = new ResultSetMappingImpl( sqlString, true );
		this.resultMappingSuppliedToCtor = false;
	}

	private IllegalArgumentException buildIncompatibleException(Class<?> resultClass, Class<?> actualResultClass) {
		final String resultClassName = resultClass.getName();
		final String actualResultClassName = actualResultClass.getName();
		if ( resultClassName.equals( actualResultClassName ) ) {
			return new IllegalArgumentException(
					"Type specified for TypedQuery [" + resultClassName +
							"] is incompatible with the query return type of the same name." +
							" Both classes have the same name but are different as they have been loaded respectively by Classloaders " +
							resultClass.getClassLoader().toString() + ", " + actualResultClass.getClassLoader().toString() +
							". This suggests a classloader bug in the Runtime executing Hibernate ORM, or in the integration code."
			);
		}
		else {
			return new IllegalArgumentException(
					"Type specified for TypedQuery [" + resultClassName +
							"] is incompatible with query return type [" + actualResultClass + "]"
			);
		}
	}

	@Override
	public String getQueryString() {
		return sqlString;
	}

	@Override
	public ParameterMetadataImplementor getParameterMetadata() {
		return parameterMetadata;
	}

	@Override
	public MutableQueryOptions getQueryOptions() {
		return queryOptions;
	}

	@Override
	public Callback getCallback() {
		if ( callback == null ) {
			callback = new CallbackImpl();
		}
		return callback;
	}

	public SessionFactoryImplementor getSessionFactory() {
		return getSession().getFactory();
	}

	@Override
	public QueryParameterBindings getQueryParameterBindings() {
		return parameterBindings;
	}

	@Override
	public QueryParameterBindings getParameterBindings() {
		return getQueryParameterBindings();
	}

	@Override
	public NamedNativeQueryMemento toMemento(String name) {
		return new NamedNativeQueryMementoImpl(
				name,
				sqlString,
				resultSetMapping.getMappingIdentifier(),
				null,
				querySpaces,
				isCacheable(),
				getCacheRegion(),
				getCacheMode(),
				getHibernateFlushMode(),
				isReadOnly(),
				getTimeout(),
				getFetchSize(),
				getComment(),
				getHints()

		);
	}

	@Override
	public LockModeType getLockMode() {
		throw new IllegalStateException( "Illegal attempt to get lock mode on a native-query" );
	}

	@Override
	public NativeQueryImplementor<R> setLockOptions(LockOptions lockOptions) {
		super.setLockOptions( lockOptions );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setLockMode(String alias, LockMode lockMode) {
		super.setLockMode( alias, lockMode );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setLockMode(LockModeType lockModeType) {
		throw new IllegalStateException( "Illegal attempt to set lock mode on a native-query" );
	}

	@Override
	public Query<R> applyGraph(@SuppressWarnings("rawtypes") RootGraph graph, GraphSemantic semantic) {
		throw new HibernateException( "A native SQL query cannot use EntityGraphs" );
	}

	@Override
	public <T> NativeQueryImplementor<T> setTupleTransformer(TupleTransformer<T> transformer) {
		return (NativeQueryImplementor<T>) super.setTupleTransformer( transformer );
	}

	@Override
	public NativeQueryImplementor<R> setResultListTransformer(ResultListTransformer<R> transformer) {
		return (NativeQueryImplementor<R>) super.setResultListTransformer( transformer );
	}

	protected Boolean isSelectQuery() {
		if ( resultMappingSuppliedToCtor
				|| resultSetMapping.getNumberOfResultBuilders() > 0
				|| isReadOnly() ) {
			return true;
		}

		return null;
	}

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Execution

	@Override
	protected void prepareForExecution() {
		if ( getSynchronizedQuerySpaces() != null && !getSynchronizedQuerySpaces().isEmpty() ) {
			// The application defined query spaces on the Hibernate NativeQuery
			// which means the query will already perform a partial flush
			// according to the defined query spaces, no need to do a full flush.
			return;
		}

		// otherwise we need to flush.  the query itself is not required to execute
		// in a transaction; if there is no transaction, the flush would throw a
		// TransactionRequiredException which would potentially break existing
		// apps, so we only do the flush if a transaction is in progress.
		//
		// NOTE : this was added for JPA initially.  Perhaps we want to only do
		// this from JPA usage?
		if ( shouldFlush() ) {
			getSession().flush();
		}
		// Reset the callback before every execution
		callback = null;
	}

	private boolean shouldFlush() {
		if ( getSession().isTransactionInProgress() ) {
			FlushMode effectiveFlushMode = getHibernateFlushMode();
			if ( effectiveFlushMode == null ) {
				effectiveFlushMode = getSession().getHibernateFlushMode();
			}

			if ( effectiveFlushMode == FlushMode.ALWAYS ) {
				return true;
			}

			if ( effectiveFlushMode == FlushMode.AUTO ) {
				return getSession().getFactory().getSessionFactoryOptions().isJpaBootstrap();
			}
		}

		return false;
	}

	@Override
	protected List<R> doList() {
		return resolveSelectQueryPlan().performList( this );
	}

	private SelectQueryPlan<R> resolveSelectQueryPlan() {
		final QueryInterpretationCache.Key cacheKey = generateSelectInterpretationsKey( resultSetMapping );
		if ( cacheKey != null ) {
			return getSession().getFactory().getQueryEngine().getInterpretationCache().resolveSelectQueryPlan(
					cacheKey,
					() -> createQueryPlan( resultSetMapping )
			);
		}
		else {
			return createQueryPlan( resultSetMapping );
		}
	}

	private NativeSelectQueryPlan<R> createQueryPlan(ResultSetMapping resultSetMapping) {
		final String sqlString = expandParameterLists();
		final NativeSelectQueryDefinition<R> queryDefinition = new NativeSelectQueryDefinition<>() {
			@Override
			public String getSqlString() {
				return sqlString;
			}

			@Override
			public boolean isCallable() {
				return false;
			}

			@Override
			public List<ParameterOccurrence> getQueryParameterOccurrences() {
				return NativeQueryImpl.this.parameterOccurrences;
			}

			@Override
			public ResultSetMapping getResultSetMapping() {
				return resultSetMapping;
			}

			@Override
			public Set<String> getAffectedTableNames() {
				return querySpaces;
			}
		};

		return getSessionFactory().getQueryEngine()
				.getNativeQueryInterpreter()
				.createQueryPlan( queryDefinition, getSessionFactory() );
	}

	private String expandParameterLists() {
		if ( parameterOccurrences == null || parameterOccurrences.isEmpty() ) {
			return sqlString;
		}
		// HHH-1123
		// Some DBs limit number of IN expressions.  For now, warn...
		final Dialect dialect = getSessionFactory().getServiceRegistry().getService( JdbcServices.class ).getJdbcEnvironment().getDialect();
		final boolean paddingEnabled = getSessionFactory().getSessionFactoryOptions().inClauseParameterPaddingEnabled();
		final int inExprLimit = dialect.getInExpressionCountLimit();

		StringBuilder sb = null;

		// Handle parameter lists
		int offset = 0;
		for ( ParameterOccurrence occurrence : parameterOccurrences ) {
			final QueryParameterImplementor<?> queryParameter = occurrence.getParameter();
			final QueryParameterBinding<?> binding = parameterBindings.getBinding( queryParameter );
			if ( !binding.isMultiValued() ) {
				continue;
			}
			final Collection<?> bindValues = binding.getBindValues();

			int bindValueCount = bindValues.size();
			int bindValueMaxCount = determineBindValueMaxCount( paddingEnabled, inExprLimit, bindValueCount );

			if ( inExprLimit > 0 && bindValueCount > inExprLimit ) {
				log.tooManyInExpressions(
						dialect.getClass().getName(),
						inExprLimit,
						queryParameter.getName() == null
								? queryParameter.getPosition().toString()
								: queryParameter.getName(),
						bindValueCount
				);
			}

			final int sourcePosition = occurrence.getSourcePosition();
			if ( sourcePosition < 0 ) {
				continue;
			}

			// check if placeholder is already immediately enclosed in parentheses
			// (ignoring whitespace)
			boolean isEnclosedInParens = true;
			for ( int i = sourcePosition - 1; i >= 0; i-- ) {
				final char ch = sqlString.charAt( i );
				if ( !Character.isWhitespace( ch ) ) {
					isEnclosedInParens = ch == '(';
					break;
				}
			}
			if ( isEnclosedInParens ) {
				for ( int i = sourcePosition + 1; i < sqlString.length(); i++ ) {
					final char ch = sqlString.charAt( i );
					if ( !Character.isWhitespace( ch ) ) {
						isEnclosedInParens = ch == ')';
						break;
					}
				}
			}

			if ( bindValueCount == 1 && isEnclosedInParens ) {
				// short-circuit for performance when only 1 value and the
				// placeholder is already enclosed in parentheses...
				continue;
			}

			if ( sb == null ) {
				sb = new StringBuilder( sqlString.length() + 20 );
				sb.append( sqlString );
			}

			final String expansionListAsString;
			// HHH-8901
			if ( bindValueMaxCount == 0 ) {
				if ( isEnclosedInParens ) {
					expansionListAsString = "null";
				}
				else {
					expansionListAsString = "(null)";
				}
			}
			else {
				// Shift 1 bit instead of multiplication by 2
				char[] chars;
				if ( isEnclosedInParens ) {
					chars = new char[( bindValueMaxCount << 1 ) - 1];
					chars[0] = '?';
					for ( int i = 1; i < bindValueMaxCount; i++ ) {
						final int index = i << 1;
						chars[index - 1] = ',';
						chars[index] = '?';
					}
				}
				else {
					chars = new char[( bindValueMaxCount << 1 ) + 1];
					chars[0] = '(';
					chars[1] = '?';
					for ( int i = 1; i < bindValueMaxCount; i++ ) {
						final int index = i << 1;
						chars[index] = ',';
						chars[index + 1] = '?';
					}
					chars[chars.length - 1] = ')';
				}

				expansionListAsString = new String(chars);
			}

			final int start = sourcePosition + offset;
			final int end = start + 1;
			sb.replace( start, end, expansionListAsString );
			offset += expansionListAsString.length() - 1;
		}
		return sb == null ? sqlString : sb.toString();
	}

	public static int determineBindValueMaxCount(boolean paddingEnabled, int inExprLimit, int bindValueCount) {
		int bindValueMaxCount = bindValueCount;

		final boolean inClauseParameterPaddingEnabled = paddingEnabled && bindValueCount > 2;

		if ( inClauseParameterPaddingEnabled ) {
			int bindValuePaddingCount = MathHelper.ceilingPowerOfTwo( bindValueCount );

			if ( inExprLimit > 0 && bindValuePaddingCount > inExprLimit ) {
				bindValuePaddingCount = inExprLimit;
			}

			if ( bindValueCount < bindValuePaddingCount ) {
				bindValueMaxCount = bindValuePaddingCount;
			}
		}
		return bindValueMaxCount;
	}

	private SelectInterpretationsKey generateSelectInterpretationsKey(JdbcValuesMappingProducer resultSetMapping) {
		if ( !isCacheable( this ) ) {
			return null;
		}

		return new SelectInterpretationsKey(
				getQueryString(),
				resultSetMapping,
				getSynchronizedQuerySpaces(),
				getQueryOptions().getTupleTransformer(),
				getQueryOptions().getResultListTransformer()
		);
	}

	private static boolean isCacheable(NativeQueryImpl<?> query) {
		// todo (6.0): unless we move the limit rendering from DeferredResultSetAccess to NativeSelectQueryPlanImpl
		//  we don't need to consider the limit here at all because that is applied on demand.
		//  It certainly is better for performance to include the limit early, but then we might trash the cache
//		if ( hasLimit( query.getQueryOptions().getLimit() ) ) {
//			return false;
//		}

		// For now, don't cache plans that have parameter lists
		return !query.parameterBindings.hasAnyMultiValuedBindings();
	}

	@Override
	public ScrollableResultsImplementor<R> scroll(ScrollMode scrollMode) {
		return resolveSelectQueryPlan().performScroll( scrollMode, this );
	}

	protected int doExecuteUpdate() {
		return resolveNonSelectQueryPlan().executeUpdate( this );
	}

	private NonSelectQueryPlan resolveNonSelectQueryPlan() {
		NonSelectQueryPlan queryPlan = null;

		final QueryInterpretationCache.Key cacheKey = generateNonSelectInterpretationsKey();
		if ( cacheKey != null ) {
			queryPlan = getSession().getFactory().getQueryEngine().getInterpretationCache().getNonSelectQueryPlan( cacheKey );
		}

		if ( queryPlan == null ) {
			final String sqlString = expandParameterLists();
			queryPlan = new NativeNonSelectQueryPlanImpl( sqlString, querySpaces, parameterOccurrences );
			if ( cacheKey != null ) {
				getSession().getFactory().getQueryEngine().getInterpretationCache().cacheNonSelectQueryPlan( cacheKey, queryPlan );
			}
		}

		return queryPlan;
	}


	protected NonSelectInterpretationsKey generateNonSelectInterpretationsKey() {
		if ( !isCacheable( this ) ) {
			return null;
		}

		// todo (6.0) - should this account for query-spaces in determining "cacheable"?
		return new NonSelectInterpretationsKey(
				getQueryString(),
				getSynchronizedQuerySpaces()
		);
	}

	@Override
	public NativeQueryImplementor<R> setCollectionKey(Object key) {
		this.collectionKey = key;
		return this;
	}

	@Override
	public NativeQueryImplementor<R> addScalar(String columnAlias) {
		return registerBuilder( Builders.scalar( columnAlias ) );
	}

	protected NativeQueryImplementor<R> registerBuilder(ResultBuilder builder) {
		resultSetMapping.addResultBuilder( builder );
		return this;
	}

	@Override
	public NativeQuery<R> addScalar(String columnAlias, @SuppressWarnings("rawtypes") BasicTypeReference type) {
		return registerBuilder(
				Builders.scalar(
						columnAlias,
						getSessionFactory().getTypeConfiguration().getBasicTypeRegistry().resolve( type )
				)
		);
	}

	@Override
	public NativeQueryImplementor<R> addScalar(String columnAlias, @SuppressWarnings("rawtypes") BasicDomainType type) {
		return registerBuilder( Builders.scalar( columnAlias, (BasicType<?>) type ) );
	}

	@Override
	public NativeQueryImplementor<R> addScalar(String columnAlias, @SuppressWarnings("rawtypes") Class javaType) {
		return registerBuilder( Builders.scalar( columnAlias, javaType, getSessionFactory() ) );
	}

	@Override
	public <C> NativeQueryImplementor<R> addScalar(
			String columnAlias,
			Class<C> jdbcJavaType,
			AttributeConverter<?, C> converter) {
		return registerBuilder( Builders.converted( columnAlias, jdbcJavaType, converter, getSessionFactory() ) );
	}

	@Override
	public <O, J> NativeQueryImplementor<R> addScalar(
			String columnAlias,
			Class<O> domainJavaType,
			Class<J> jdbcJavaType,
			AttributeConverter<O, J> converter) {
		return registerBuilder( Builders.converted( columnAlias, domainJavaType, jdbcJavaType, converter, getSessionFactory() ) );
	}

	@Override
	public <C> NativeQueryImplementor<R> addScalar(
			String columnAlias,
			Class<C> relationalJavaType,
			Class<? extends AttributeConverter<?, C>> converter) {
		return registerBuilder( Builders.converted( columnAlias, relationalJavaType, converter, getSessionFactory() ) );
	}

	@Override
	public <O, J> NativeQueryImplementor<R> addScalar(
			String columnAlias,
			Class<O> domainJavaType,
			Class<J> jdbcJavaType,
			Class<? extends AttributeConverter<O, J>> converterJavaType) {
		return registerBuilder( Builders.converted( columnAlias, domainJavaType, jdbcJavaType, converterJavaType, getSessionFactory() ) );
	}

	@Override
	public <J> InstantiationResultNode<J> addInstantiation(Class<J> targetJavaType) {
		final DynamicResultBuilderInstantiation<J> builder = Builders.instantiation(
				targetJavaType,
				getSessionFactory()
		);
		registerBuilder( builder );
		return builder;
	}

	@Override
	public NativeQueryImplementor<R> addAttributeResult(
			String columnAlias,
			@SuppressWarnings("rawtypes") Class entityJavaType,
			String attributePath) {
		return addAttributeResult( columnAlias, entityJavaType.getName(), attributePath );
	}

	@Override
	public NativeQueryImplementor<R> addAttributeResult(
			String columnAlias,
			String entityName,
			String attributePath) {
		registerBuilder( Builders.attributeResult( columnAlias, entityName, attributePath, getSessionFactory() ) );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> addAttributeResult(
			String columnAlias,
			@SuppressWarnings("rawtypes") SingularAttribute attribute) {
		registerBuilder( Builders.attributeResult( columnAlias, attribute ) );
		return this;
	}

	@Override
	public DynamicResultBuilderEntityStandard addRoot(String tableAlias, String entityName) {
		final DynamicResultBuilderEntityStandard resultBuilder = Builders.entity(
				tableAlias,
				entityName,
				getSessionFactory()
		);
		resultSetMapping.addResultBuilder( resultBuilder );
		return resultBuilder;
	}

	@Override
	public DynamicResultBuilderEntityStandard addRoot(String tableAlias, @SuppressWarnings("rawtypes") Class entityType) {
		return addRoot( tableAlias, entityType.getName() );
	}

	@Override
	public NativeQueryImplementor<R> addEntity(String entityName) {
		return addEntity( StringHelper.unqualify( entityName ), entityName );
	}

	@Override
	public NativeQueryImplementor<R> addEntity(String tableAlias, String entityName) {
		registerBuilder( Builders.entityCalculated( tableAlias, entityName, getSessionFactory() ) );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> addEntity(String tableAlias, String entityName, LockMode lockMode) {
		registerBuilder( Builders.entityCalculated( tableAlias, entityName, lockMode, getSessionFactory() ) );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> addEntity(@SuppressWarnings("rawtypes") Class entityType) {
		return addEntity( entityType.getName() );
	}

	@Override
	public NativeQueryImplementor<R> addEntity(String tableAlias, @SuppressWarnings("rawtypes") Class entityClass) {
		return addEntity( tableAlias, entityClass.getName() );
	}

	@Override
	public NativeQueryImplementor<R> addEntity(String tableAlias, @SuppressWarnings("rawtypes") Class entityClass, LockMode lockMode) {
		return addEntity( tableAlias, entityClass.getName(), lockMode );
	}

	@Override
	public FetchReturn addFetch(String tableAlias, String ownerTableAlias, String joinPropertyName) {
		final DynamicFetchBuilderLegacy fetchBuilder = Builders.fetch( tableAlias, ownerTableAlias, joinPropertyName );
		resultSetMapping.addLegacyFetchBuilder( fetchBuilder );
		return fetchBuilder;
	}

	@Override
	public NativeQueryImplementor<R> addJoin(String tableAlias, String path) {
		createFetchJoin( tableAlias, path );
		return this;
	}

	private FetchReturn createFetchJoin(String tableAlias, String path) {
		int loc = path.indexOf( '.' );
		if ( loc < 0 ) {
			throw new QueryException( "not a property path: " + path );
		}
		final String ownerTableAlias = path.substring( 0, loc );
		final String joinedPropertyName = path.substring( loc + 1 );
		return addFetch( tableAlias, ownerTableAlias, joinedPropertyName );
	}

	@Override
	public NativeQueryImplementor<R> addJoin(String tableAlias, String ownerTableAlias, String joinPropertyName) {
		addFetch( tableAlias, ownerTableAlias, joinPropertyName );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> addJoin(String tableAlias, String path, LockMode lockMode) {
		createFetchJoin( tableAlias, path ).setLockMode( lockMode );
		return this;
	}

	@Override
	public Collection<String> getSynchronizedQuerySpaces() {
		return querySpaces;
	}

	@Override
	public NativeQueryImplementor<R> addSynchronizedQuerySpace(String querySpace) {
		addQuerySpaces( querySpace );
		return this;
	}

	protected void addQuerySpaces(String... spaces) {
		if ( spaces != null ) {
			if ( querySpaces == null ) {
				querySpaces = new HashSet<>();
			}
			Collections.addAll( querySpaces, spaces );
		}
	}

	protected void addQuerySpaces(Serializable... spaces) {
		if ( spaces != null ) {
			if ( querySpaces == null ) {
				querySpaces = new HashSet<>();
			}
			Collections.addAll( querySpaces, (String[]) spaces );
		}
	}

	@Override
	public NativeQueryImplementor<R> addSynchronizedEntityName(String entityName) throws MappingException {
		addQuerySpaces( getSession().getFactory().getMetamodel().entityPersister( entityName ).getQuerySpaces() );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> addSynchronizedEntityClass(@SuppressWarnings("rawtypes") Class entityClass) throws MappingException {
		addQuerySpaces( getSession().getFactory().getMetamodel().entityPersister( entityClass.getName() ).getQuerySpaces() );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setHibernateFlushMode(FlushMode flushMode) {
		super.setHibernateFlushMode( flushMode );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setFlushMode(FlushModeType flushModeType) {
		super.setFlushMode( flushModeType );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setCacheMode(CacheMode cacheMode) {
		super.setCacheMode( cacheMode );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setCacheable(boolean cacheable) {
		super.setCacheable( cacheable );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setCacheRegion(String cacheRegion) {
		super.setCacheRegion( cacheRegion );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setTimeout(int timeout) {
		super.setTimeout( timeout );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setFetchSize(int fetchSize) {
		super.setFetchSize( fetchSize );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setReadOnly(boolean readOnly) {
		super.setReadOnly( readOnly );
		return this;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T unwrap(Class<T> javaType) {
		if ( javaType.isAssignableFrom( getClass() ) ) {
			return (T) this;
		}

		if ( javaType.isAssignableFrom( ParameterMetadata.class ) ) {
			return (T) parameterMetadata;
		}

		if ( javaType.isAssignableFrom( QueryParameterBindings.class ) ) {
			return (T) parameterBindings;
		}

		if ( javaType.isAssignableFrom( EntityManager.class ) ) {
			return (T) getSession();
		}

		if ( javaType.isAssignableFrom( EntityManagerFactory.class ) ) {
			return (T) getSession().getFactory();
		}

		throw new PersistenceException( "Unrecognized unwrap type [" + javaType.getName() + "]" );
	}

	@Override
	public NativeQueryImplementor<R> setComment(String comment) {
		super.setComment( comment );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> addQueryHint(String hint) {
		super.addQueryHint( hint );
		return this;
	}

	@Override
	protected void collectHints(Map<String, Object> hints) {
		super.collectHints( hints );

		putIfNotNull( hints, HINT_NATIVE_LOCKMODE, getLockOptions().getLockMode() );
	}

	@Override
	protected boolean applySynchronizeSpacesHint(Object value) {
		if ( value instanceof String ) {
			addSynchronizedQuerySpace( (String) value );
		}
		else if ( value instanceof String[] ) {
			for (String string : (String[]) value) {
				addSynchronizedQuerySpace(string);
			}
		}
		else if ( value instanceof Class ) {
			addSynchronizedEntityClass( (Class<?>) value );
		}
		else if ( value instanceof Class[] ) {
			for (Class<?> aClass : (Class<?>[]) value) {
				addSynchronizedEntityClass(aClass);
			}
		}
		else if ( value instanceof List ) {
			final List<?> list = (List<?>) value;
			list.forEach( this::applySynchronizeSpacesHint );
		}
		else if ( value instanceof Collection ) {
			final Collection<?> values = (Collection<?>) value;
			for ( Object element : values ) {
				applySynchronizeSpacesHint( element );
			}
		}
		else {
			return false;
		}

		return true;
	}

	@Override
	protected boolean applyNativeQueryLockMode(Object value) {
		if ( value instanceof LockMode ) {
			applyHibernateLockModeHint( (LockMode) value );
		}
		else if ( value instanceof LockModeType ) {
			applyLockModeTypeHint( (LockModeType) value );
		}
		else if ( value instanceof String ) {
			applyHibernateLockModeHint( LockModeTypeHelper.interpretLockMode( value ) );
		}
		else {
			throw new IllegalArgumentException(
					String.format(
							"Native lock-mode hint [%s] must specify %s or %s.  Encountered type : %s",
							HINT_NATIVE_LOCKMODE,
							LockMode.class.getName(),
							LockModeType.class.getName(),
							value.getClass().getName()
					)
			);
		}

		return true;
	}




	@Override
	public NativeQueryImplementor<R> setParameter(String name, Object value) {
		super.setParameter( name, value );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameter(String name, P value, Class<P> javaType) {
		super.setParameter( name, value, javaType );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameter(String name, P value, BindableType<P> type) {
		super.setParameter( name, value, type );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameter(String name, Calendar value, TemporalType temporalType) {
		super.setParameter( name, value, temporalType );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameter(String name, Instant value, TemporalType temporalType) {
		super.setParameter( name, value, temporalType );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameter(String name, Date value, TemporalType temporalType) {
		super.setParameter( name, value, temporalType );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameter(int position, Object value) {
		super.setParameter( position, value );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameter(int position, P value, Class<P> javaType) {
		super.setParameter( position, value, javaType );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameter(int position, P value, BindableType<P> type) {
		super.setParameter( position, value, type );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameter(int position, Instant value, TemporalType temporalType) {
		super.setParameter( position, value, temporalType );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameter(int position, Calendar value, TemporalType temporalType) {
		super.setParameter( position, value, temporalType );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameter(int position, Date value, TemporalType temporalType) {
		super.setParameter( position, value, temporalType );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameter(QueryParameter<P> parameter, P value) {
		super.setParameter( parameter, value );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameter(QueryParameter<P> parameter, P value, Class<P> javaType) {
		super.setParameter( parameter, value, javaType );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameter(QueryParameter<P> parameter, P value, BindableType<P> type) {
		super.setParameter( parameter, value, type );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameter(Parameter<P> parameter, P value) {
		super.setParameter( parameter, value );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameter(Parameter<Calendar> param, Calendar value, TemporalType temporalType) {
		super.setParameter( param, value, temporalType );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameter(Parameter<Date> param, Date value, TemporalType temporalType) {
		super.setParameter( param, value, temporalType );
		return this;
	}









	@Override
	public NativeQueryImplementor<R> setParameterList(String name, @SuppressWarnings("rawtypes") Collection values) {
		super.setParameterList( name, values );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(String name, Collection<? extends P> values, Class<P> javaType) {
		super.setParameterList( name, values, javaType );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(String name, Collection<? extends P> values, BindableType<P> type) {
		super.setParameterList( name, values, type );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameterList(String name, Object[] values) {
		super.setParameterList( name, values );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(String name, P[] values, Class<P> javaType) {
		super.setParameterList( name, values, javaType );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(String name, P[] values, BindableType<P> type) {
		super.setParameterList( name, values, type );
		return this;
	}




	@Override
	public NativeQueryImplementor<R> setParameterList(int position, @SuppressWarnings("rawtypes") Collection values) {
		super.setParameterList( position, values );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(int position, Collection<? extends P> values, Class<P> javaType) {
		super.setParameterList( position, values, javaType );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(int position, Collection<? extends P> values, BindableType<P> type) {
		super.setParameterList( position, values, type );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setParameterList(int position, Object[] values) {
		super.setParameterList( position, values );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(int position, P[] values, Class<P> javaType) {
		super.setParameterList( position, values, javaType );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(int position, P[] values, BindableType<P> type) {
		super.setParameterList( position, values, type );
		return this;
	}



	@Override
	public <P> NativeQueryImplementor<R> setParameterList(QueryParameter<P> parameter, Collection<? extends P> values) {
		super.setParameterList( parameter, values );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(QueryParameter<P> parameter, Collection<? extends P> values, Class<P> javaType) {
		super.setParameterList( parameter, values, javaType );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(QueryParameter<P> parameter, Collection<? extends P> values, BindableType<P> type) {
		super.setParameterList( parameter, values, type );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(QueryParameter<P> parameter, P[] values) {
		super.setParameterList( parameter, values );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(QueryParameter<P> parameter, P[] values, Class<P> javaType) {
		super.setParameterList( parameter, values, javaType );
		return this;
	}

	@Override
	public <P> NativeQueryImplementor<R> setParameterList(QueryParameter<P> parameter, P[] values, BindableType<P> type) {
		super.setParameterList( parameter, values, type );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setProperties(@SuppressWarnings("rawtypes") Map map) {
		super.setProperties( map );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setProperties(Object bean) {
		super.setProperties( bean );
		return this;
	}

	@Override @Deprecated @SuppressWarnings("deprecation")
	public <S> NativeQueryImplementor<S> setResultTransformer(ResultTransformer<S> transformer) {
		return setTupleTransformer( transformer ).setResultListTransformer( transformer );
	}

	@Override
	public NativeQueryImplementor<R> setMaxResults(int maxResult) {
		super.setMaxResults( maxResult );
		return this;
	}

	@Override
	public NativeQueryImplementor<R> setFirstResult(int startPosition) {
		super.setFirstResult( startPosition );
		return this;
	}



	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Hints

	@Override
	public NativeQueryImplementor<R> setHint(String hintName, Object value) {
		super.setHint( hintName, value );
		return this;
	}

	@Override
	protected void applyEntityGraphQueryHint(String hintName, @SuppressWarnings("rawtypes") RootGraphImplementor entityGraph) {
		throw new HibernateException( "A native SQL query cannot use EntityGraphs" );
	}

	private static class ParameterInterpretationImpl implements ParameterInterpretation {
		private final String sqlString;
		private final List<ParameterOccurrence> parameterList;
		private final Map<Integer, QueryParameterImplementor<?>> positionalParameters;
		private final Map<String, QueryParameterImplementor<?>> namedParameters;

		public ParameterInterpretationImpl(ParameterRecognizerImpl parameterRecognizer) {
			this.sqlString = parameterRecognizer.getAdjustedSqlString();
			this.parameterList = parameterRecognizer.getParameterList();
			this.positionalParameters = parameterRecognizer.getPositionalQueryParameters();
			this.namedParameters = parameterRecognizer.getNamedQueryParameters();
		}

		@Override
		public List<ParameterOccurrence> getOrderedParameterOccurrences() {
			return parameterList;
		}

		@Override
		public ParameterMetadataImplementor toParameterMetadata(SharedSessionContractImplementor session1) {
			return new ParameterMetadataImpl( positionalParameters, namedParameters );
		}

		@Override
		public String getAdjustedSqlString() {
			return sqlString;
		}

		@Override
		public String toString() {
			final StringBuilder buffer = new StringBuilder( "ParameterInterpretationImpl (" )
					.append( sqlString )
					.append( ") : {" );

			for ( int i = 0, size = parameterList.size(); i < size; i++ ) {
				buffer.append( System.lineSeparator() ).append( "    " );

				if ( i != size - 1 ) {
					buffer.append( "," );
				}
			}

			return buffer.append( System.lineSeparator() ).append( "}" ).toString();
		}
	}
}
