/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.dialect;

import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.ScrollMode;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.dialect.identity.HANAIdentityColumnSupport;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.LimitOffsetLimitHandler;
import org.hibernate.dialect.sequence.HANASequenceSupport;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.config.spi.ConfigurationService.Converter;
import org.hibernate.engine.config.spi.StandardConverters;
import org.hibernate.engine.jdbc.*;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.jdbc.env.spi.*;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.hibernate.exception.LockTimeoutException;
import org.hibernate.exception.SQLGrammarException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.JdbcExceptionHelper;
import org.hibernate.mapping.Table;
import org.hibernate.procedure.internal.StandardCallableStatementSupport;
import org.hibernate.procedure.spi.CallableStatementSupport;
import org.hibernate.query.NullOrdering;
import org.hibernate.query.TemporalUnit;
import org.hibernate.query.spi.QueryEngine;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstNodeRenderingMode;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.tool.schema.extract.internal.SequenceInformationExtractorHANADatabaseImpl;
import org.hibernate.tool.schema.extract.spi.SequenceInformationExtractor;
import org.hibernate.tool.schema.internal.StandardTableExporter;
import org.hibernate.tool.schema.spi.Exporter;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.DataHelper;
import org.hibernate.type.descriptor.java.JavaTypeDescriptor;
import org.hibernate.type.descriptor.jdbc.*;
import org.hibernate.type.internal.StandardBasicTypeImpl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An abstract base class for SAP HANA dialects.
 * <p>
 * For more information on interacting with the SAP HANA database, refer to the
 * <a href="https://help.sap.com/viewer/4fe29514fd584807ac9f2a04f6754767/">SAP HANA SQL and System Views Reference</a>
 * and the <a href=
 * "https://help.sap.com/viewer/0eec0d68141541d1b07893a39944924e/latest/en-US/434e2962074540e18c802fd478de86d6.html">SAP
 * HANA Client Interface Programming Reference</a>.
 * <p>
 * Note: This dialect is configured to create foreign keys with {@code on update cascade}.
 *
 * @author <a href="mailto:andrew.clemons@sap.com">Andrew Clemons</a>
 * @author <a href="mailto:jonathan.bregler@sap.com">Jonathan Bregler</a>
 */
public abstract class AbstractHANADialect extends Dialect {

	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( AbstractHANADialect.class );

	private static class CloseSuppressingReader extends FilterReader {

		protected CloseSuppressingReader(final Reader in) {
			super( in );
		}

		@Override
		public void close() {
			// do not close
		}
	}

	private static class CloseSuppressingInputStream extends FilterInputStream {

		protected CloseSuppressingInputStream(final InputStream in) {
			super( in );
		}

		@Override
		public void close() {
			// do not close
		}
	}

	private static class MaterializedBlob implements Blob {

		private byte[] bytes = null;

		public MaterializedBlob(byte[] bytes) {
			this.setBytes( bytes );
		}

		@Override
		public long length() throws SQLException {
			return this.getBytes().length;
		}

		@Override
		public byte[] getBytes(long pos, int length) throws SQLException {
			return Arrays.copyOfRange( this.bytes, (int) ( pos - 1 ), (int) ( pos - 1 + length ) );
		}

		@Override
		public InputStream getBinaryStream() throws SQLException {
			return new ByteArrayInputStream( this.getBytes() );
		}

		@Override
		public long position(byte[] pattern, long start) throws SQLException {
			throw new SQLFeatureNotSupportedException();
		}

		@Override
		public long position(Blob pattern, long start) throws SQLException {
			throw new SQLFeatureNotSupportedException();
		}

		@Override
		public int setBytes(long pos, byte[] bytes) throws SQLException {
			int bytesSet = 0;
			if ( this.bytes.length < pos - 1 + bytes.length ) {
				this.bytes = Arrays.copyOf( this.bytes, (int) ( pos - 1 + bytes.length ) );
			}
			for ( int i = 0; i < bytes.length && i < this.bytes.length; i++, bytesSet++ ) {
				this.bytes[(int) ( i + pos - 1 )] = bytes[i];
			}
			return bytesSet;
		}

		@Override
		public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
			int bytesSet = 0;
			if ( this.bytes.length < pos - 1 + len ) {
				this.bytes = Arrays.copyOf( this.bytes, (int) ( pos - 1 + len ) );
			}
			for ( int i = offset; i < len && i < this.bytes.length; i++, bytesSet++ ) {
				this.bytes[(int) ( i + pos - 1 )] = bytes[i];
			}
			return bytesSet;
		}

		@Override
		public OutputStream setBinaryStream(long pos) throws SQLException {
			return new ByteArrayOutputStream() {

				{
					this.buf = getBytes();
				}
			};
		}

		@Override
		public void truncate(long len) throws SQLException {
			this.setBytes( Arrays.copyOf( this.getBytes(), (int) len ) );
		}

		@Override
		public void free() throws SQLException {
			this.setBytes( null );
		}

		@Override
		public InputStream getBinaryStream(long pos, long length) throws SQLException {
			return new ByteArrayInputStream( this.getBytes(), (int) ( pos - 1 ), (int) length );
		}

		byte[] getBytes() {
			return this.bytes;
		}

		void setBytes(byte[] bytes) {
			this.bytes = bytes;
		}

	}

	private static class MaterializedNClob implements NClob {

		private String data = null;

		public MaterializedNClob(String data) {
			this.data = data;
		}

		@Override
		public void truncate(long len) throws SQLException {
			this.data = "";
		}

		@Override
		public int setString(long pos, String str, int offset, int len) throws SQLException {
			this.data = this.data.substring( 0, (int) ( pos - 1 ) ) + str.substring( offset, offset + len )
					+ this.data.substring( (int) ( pos - 1 + len ) );
			return len;
		}

		@Override
		public int setString(long pos, String str) throws SQLException {
			this.data = this.data.substring( 0, (int) ( pos - 1 ) ) + str + this.data.substring( (int) ( pos - 1 + str.length() ) );
			return str.length();
		}

		@Override
		public Writer setCharacterStream(long pos) throws SQLException {
			throw new SQLFeatureNotSupportedException();
		}

		@Override
		public OutputStream setAsciiStream(long pos) throws SQLException {
			throw new SQLFeatureNotSupportedException();
		}

		@Override
		public long position(Clob searchstr, long start) throws SQLException {
			return this.data.indexOf( DataHelper.extractString( searchstr ), (int) ( start - 1 ) );
		}

		@Override
		public long position(String searchstr, long start) throws SQLException {
			return this.data.indexOf( searchstr, (int) ( start - 1 ) );
		}

		@Override
		public long length() throws SQLException {
			return this.data.length();
		}

		@Override
		public String getSubString(long pos, int length) throws SQLException {
			return this.data.substring( (int) ( pos - 1 ), (int) ( pos - 1 + length ) );
		}

		@Override
		public Reader getCharacterStream(long pos, long length) throws SQLException {
			return new StringReader( this.data.substring( (int) ( pos - 1 ), (int) ( pos - 1 + length ) ) );
		}

		@Override
		public Reader getCharacterStream() throws SQLException {
			return new StringReader( this.data );
		}

		@Override
		public InputStream getAsciiStream() throws SQLException {
			return new ByteArrayInputStream( this.data.getBytes( StandardCharsets.ISO_8859_1 ) );
		}

		@Override
		public void free() throws SQLException {
			this.data = null;
		}
	}

	private static class HANAStreamBlobTypeDescriptor implements JdbcTypeDescriptor {

		private static final long serialVersionUID = -2476600722093442047L;

		final int maxLobPrefetchSize;

		public HANAStreamBlobTypeDescriptor(int maxLobPrefetchSize) {
			this.maxLobPrefetchSize = maxLobPrefetchSize;
		}

		@Override
		public String getFriendlyName() {
			return "BLOB (hana-stream)";
		}

		@Override
		public String toString() {
			return "HANAStreamBlobTypeDescriptor";
		}

		@Override
		public int getJdbcType() {
			return Types.BLOB;
		}

		@Override
		public boolean canBeRemapped() {
			return true;
		}

		@Override
		public <X> ValueBinder<X> getBinder(JavaTypeDescriptor<X> javaTypeDescriptor) {
			return new BasicBinder<X>( javaTypeDescriptor, this ) {

				@Override
				protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options) throws SQLException {
					final BinaryStream binaryStream = javaTypeDescriptor.unwrap( value, BinaryStream.class, options );
					if ( value instanceof BlobImplementer ) {
						try ( InputStream is = new CloseSuppressingInputStream( binaryStream.getInputStream() ) ) {
							st.setBinaryStream( index, is, binaryStream.getLength() );
						}
						catch (IOException e) {
							// can't happen => ignore
						}
					}
					else {
						st.setBinaryStream( index, binaryStream.getInputStream(), binaryStream.getLength() );
					}
				}

				@Override
				protected void doBind(CallableStatement st, X value, String name, WrapperOptions options) throws SQLException {
					final BinaryStream binaryStream = javaTypeDescriptor.unwrap( value, BinaryStream.class, options );
					if ( value instanceof BlobImplementer ) {
						try ( InputStream is = new CloseSuppressingInputStream( binaryStream.getInputStream() ) ) {
							st.setBinaryStream( name, is, binaryStream.getLength() );
						}
						catch (IOException e) {
							// can't happen => ignore
						}
					}
					else {
						st.setBinaryStream( name, binaryStream.getInputStream(), binaryStream.getLength() );
					}
				}
			};
		}

		@Override
		public <X> ValueExtractor<X> getExtractor(JavaTypeDescriptor<X> javaTypeDescriptor) {
			return new BasicExtractor<X>( javaTypeDescriptor, this ) {

				@Override
				protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
					Blob rsBlob = rs.getBlob( paramIndex );
					if ( rsBlob == null || rsBlob.length() < HANAStreamBlobTypeDescriptor.this.maxLobPrefetchSize ) {
						return javaTypeDescriptor.wrap( rsBlob, options );
					}
					Blob blob = new MaterializedBlob( DataHelper.extractBytes( rsBlob.getBinaryStream() ) );
					return javaTypeDescriptor.wrap( blob, options );
				}

				@Override
				protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
					return javaTypeDescriptor.wrap( statement.getBlob( index ), options );
				}

				@Override
				protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
					return javaTypeDescriptor.wrap( statement.getBlob( name ), options );
				}
			};
		}

	}

	// the ClobTypeDescriptor and NClobTypeDescriptor for HANA are slightly
	// changed from the standard ones. The HANA JDBC driver currently closes any
	// stream passed in via
	// PreparedStatement.setCharacterStream(int,Reader,long)
	// after the stream has been processed. this causes problems later if we are
	// using non-contextual lob creation and HANA then closes our StringReader.
	// see test case LobLocatorTest

	private static class HANAClobTypeDescriptor extends ClobTypeDescriptor {
		@Override
		public String toString() {
			return "HANAClobTypeDescriptor";
		}

		/** serial version uid. */
		private static final long serialVersionUID = -379042275442752102L;

		final int maxLobPrefetchSize;
		final boolean useUnicodeStringTypes;

		public HANAClobTypeDescriptor(int maxLobPrefetchSize, boolean useUnicodeStringTypes) {
			this.maxLobPrefetchSize = maxLobPrefetchSize;
			this.useUnicodeStringTypes = useUnicodeStringTypes;
		}

		@Override
		public <X> BasicBinder<X> getClobBinder(final JavaTypeDescriptor<X> javaTypeDescriptor) {
			return new BasicBinder<X>( javaTypeDescriptor, this ) {

				@Override
				protected void doBind(final PreparedStatement st, final X value, final int index, final WrapperOptions options) throws SQLException {
					final CharacterStream characterStream = javaTypeDescriptor.unwrap( value, CharacterStream.class, options );

					if ( value instanceof ClobImplementer ) {
						try ( Reader r = new CloseSuppressingReader( characterStream.asReader() ) ) {
							st.setCharacterStream( index, r, characterStream.getLength() );
						}
						catch (IOException e) {
							// can't happen => ignore
						}
					}
					else {
						st.setCharacterStream( index, characterStream.asReader(), characterStream.getLength() );
					}

				}

				@Override
				protected void doBind(CallableStatement st, X value, String name, WrapperOptions options) throws SQLException {
					final CharacterStream characterStream = javaTypeDescriptor.unwrap( value, CharacterStream.class, options );

					if ( value instanceof ClobImplementer ) {
						try ( Reader r = new CloseSuppressingReader( characterStream.asReader() ) ) {
							st.setCharacterStream( name, r, characterStream.getLength() );
						}
						catch (IOException e) {
							// can't happen => ignore
						}
					}
					else {
						st.setCharacterStream( name, characterStream.asReader(), characterStream.getLength() );
					}
				}
			};
		}

		@Override
		public <X> ValueExtractor<X> getExtractor(JavaTypeDescriptor<X> javaTypeDescriptor) {
			return new BasicExtractor<X>( javaTypeDescriptor, this ) {

				@Override
				protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
					Clob rsClob;
					if ( HANAClobTypeDescriptor.this.useUnicodeStringTypes ) {
						rsClob = rs.getNClob( paramIndex );
					}
					else {
						rsClob = rs.getClob( paramIndex );
					}

					if ( rsClob == null || rsClob.length() < HANAClobTypeDescriptor.this.maxLobPrefetchSize ) {
						return javaTypeDescriptor.wrap( rsClob, options );
					}
					Clob clob = new MaterializedNClob( DataHelper.extractString( rsClob ) );
					return javaTypeDescriptor.wrap( clob, options );
				}

				@Override
				protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
					return javaTypeDescriptor.wrap( statement.getClob( index ), options );
				}

				@Override
				protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
					return javaTypeDescriptor.wrap( statement.getClob( name ), options );
				}
			};
		}

		public int getMaxLobPrefetchSize() {
			return this.maxLobPrefetchSize;
		}

		public boolean isUseUnicodeStringTypes() {
			return this.useUnicodeStringTypes;
		}
	}

	private static class HANANClobTypeDescriptor extends NClobTypeDescriptor {

		/** serial version uid. */
		private static final long serialVersionUID = 5651116091681647859L;

		final int maxLobPrefetchSize;

		public HANANClobTypeDescriptor(int maxLobPrefetchSize) {
			this.maxLobPrefetchSize = maxLobPrefetchSize;
		}

		@Override
		public String toString() {
			return "HANANClobTypeDescriptor";
		}

		@Override
		public <X> BasicBinder<X> getNClobBinder(final JavaTypeDescriptor<X> javaTypeDescriptor) {
			return new BasicBinder<X>( javaTypeDescriptor, this ) {

				@Override
				protected void doBind(final PreparedStatement st, final X value, final int index, final WrapperOptions options) throws SQLException {
					final CharacterStream characterStream = javaTypeDescriptor.unwrap( value, CharacterStream.class, options );

					if ( value instanceof NClobImplementer ) {
						try ( Reader r = new CloseSuppressingReader( characterStream.asReader() ) ) {
							st.setCharacterStream( index, r, characterStream.getLength() );
						}
						catch (IOException e) {
							// can't happen => ignore
						}
					}
					else {
						st.setCharacterStream( index, characterStream.asReader(), characterStream.getLength() );
					}

				}

				@Override
				protected void doBind(CallableStatement st, X value, String name, WrapperOptions options) throws SQLException {
					final CharacterStream characterStream = javaTypeDescriptor.unwrap( value, CharacterStream.class, options );

					if ( value instanceof NClobImplementer ) {
						try ( Reader r = new CloseSuppressingReader( characterStream.asReader() ) ) {
							st.setCharacterStream( name, r, characterStream.getLength() );
						}
						catch (IOException e) {
							// can't happen => ignore
						}
					}
					else {
						st.setCharacterStream( name, characterStream.asReader(), characterStream.getLength() );
					}
				}
			};
		}

		@Override
		public <X> ValueExtractor<X> getExtractor(JavaTypeDescriptor<X> javaTypeDescriptor) {
			return new BasicExtractor<X>( javaTypeDescriptor, this ) {

				@Override
				protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
					NClob rsNClob = rs.getNClob( paramIndex );
					if ( rsNClob == null || rsNClob.length() < HANANClobTypeDescriptor.this.maxLobPrefetchSize ) {
						return javaTypeDescriptor.wrap( rsNClob, options );
					}
					NClob nClob = new MaterializedNClob( DataHelper.extractString( rsNClob ) );
					return javaTypeDescriptor.wrap( nClob, options );
				}

				@Override
				protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
					return javaTypeDescriptor.wrap( statement.getNClob( index ), options );
				}

				@Override
				protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
					return javaTypeDescriptor.wrap( statement.getNClob( name ), options );
				}
			};
		}

		public int getMaxLobPrefetchSize() {
			return this.maxLobPrefetchSize;
		}
	}

	public static class HANABlobTypeDescriptor implements JdbcTypeDescriptor {

		private static final long serialVersionUID = 5874441715643764323L;

		final int maxLobPrefetchSize;

		final HANAStreamBlobTypeDescriptor hanaStreamBlobTypeDescriptor;

		public HANABlobTypeDescriptor(int maxLobPrefetchSize) {
			this.maxLobPrefetchSize = maxLobPrefetchSize;
			this.hanaStreamBlobTypeDescriptor = new HANAStreamBlobTypeDescriptor( maxLobPrefetchSize );
		}

		@Override
		public int getJdbcType() {
			return Types.BLOB;
		}

		@Override
		public String getFriendlyName() {
			return "BLOB (hana)";
		}

		@Override
		public String toString() {
			return "HANABlobTypeDescriptor";
		}

		@Override
		public boolean canBeRemapped() {
			return true;
		}

		@Override
		public <X> ValueExtractor<X> getExtractor(final JavaTypeDescriptor<X> javaTypeDescriptor) {
			return new BasicExtractor<X>( javaTypeDescriptor, this ) {

				@Override
				protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
					Blob rsBlob = rs.getBlob( paramIndex );
					if ( rsBlob == null || rsBlob.length() < HANABlobTypeDescriptor.this.maxLobPrefetchSize ) {
						return javaTypeDescriptor.wrap( rsBlob, options );
					}
					Blob blob = new MaterializedBlob( DataHelper.extractBytes( rsBlob.getBinaryStream() ) );
					return javaTypeDescriptor.wrap( blob, options );
				}

				@Override
				protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
					return javaTypeDescriptor.wrap( statement.getBlob( index ), options );
				}

				@Override
				protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
					return javaTypeDescriptor.wrap( statement.getBlob( name ), options );
				}
			};
		}

		@Override
		public <X> BasicBinder<X> getBinder(final JavaTypeDescriptor<X> javaTypeDescriptor) {
			return new BasicBinder<X>( javaTypeDescriptor, this ) {

				@Override
				protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options) throws SQLException {
					JdbcTypeDescriptor descriptor = BlobTypeDescriptor.BLOB_BINDING;
					if ( byte[].class.isInstance( value ) ) {
						// performance shortcut for binding BLOB data in byte[] format
						descriptor = BlobTypeDescriptor.PRIMITIVE_ARRAY_BINDING;
					}
					else if ( options.useStreamForLobBinding() ) {
						descriptor = HANABlobTypeDescriptor.this.hanaStreamBlobTypeDescriptor;
					}
					descriptor.getBinder( javaTypeDescriptor ).bind( st, value, index, options );
				}

				@Override
				protected void doBind(CallableStatement st, X value, String name, WrapperOptions options) throws SQLException {
					JdbcTypeDescriptor descriptor = BlobTypeDescriptor.BLOB_BINDING;
					if ( byte[].class.isInstance( value ) ) {
						// performance shortcut for binding BLOB data in byte[] format
						descriptor = BlobTypeDescriptor.PRIMITIVE_ARRAY_BINDING;
					}
					else if ( options.useStreamForLobBinding() ) {
						descriptor = HANABlobTypeDescriptor.this.hanaStreamBlobTypeDescriptor;
					}
					descriptor.getBinder( javaTypeDescriptor ).bind( st, value, name, options );
				}
			};
		}

		public int getMaxLobPrefetchSize() {
			return this.maxLobPrefetchSize;
		}
	}

	// Set the LOB prefetch size. LOBs larger than this value will be read into memory as the HANA JDBC driver closes
	// the LOB when the result set is closed.
	private static final String MAX_LOB_PREFETCH_SIZE_PARAMETER_NAME = "hibernate.dialect.hana.max_lob_prefetch_size";
	// Use TINYINT instead of the native BOOLEAN type
	private static final String USE_LEGACY_BOOLEAN_TYPE_PARAMETER_NAME = "hibernate.dialect.hana.use_legacy_boolean_type";
	// Use unicode (NVARCHAR, NCLOB, etc.) instead of non-unicode (VARCHAR, CLOB) string types
	private static final String USE_UNICODE_STRING_TYPES_PARAMETER_NAME = "hibernate.dialect.hana.use_unicode_string_types";
	// Read and write double-typed fields as BigDecimal instead of Double to get around precision issues of the HANA
	// JDBC driver (https://service.sap.com/sap/support/notes/2590160)
	private static final String TREAT_DOUBLE_TYPED_FIELDS_AS_DECIMAL_PARAMETER_NAME = "hibernate.dialect.hana.treat_double_typed_fields_as_decimal";

	private static final int MAX_LOB_PREFETCH_SIZE_DEFAULT_VALUE = 1024;
	private static final Boolean USE_LEGACY_BOOLEAN_TYPE_DEFAULT_VALUE = Boolean.FALSE;
	private static final Boolean TREAT_DOUBLE_TYPED_FIELDS_AS_DECIMAL_DEFAULT_VALUE = Boolean.FALSE;

	private HANANClobTypeDescriptor nClobTypeDescriptor = new HANANClobTypeDescriptor( MAX_LOB_PREFETCH_SIZE_DEFAULT_VALUE );

	private HANABlobTypeDescriptor blobTypeDescriptor = new HANABlobTypeDescriptor( MAX_LOB_PREFETCH_SIZE_DEFAULT_VALUE );

	private HANAClobTypeDescriptor clobTypeDescriptor;

	private boolean useLegacyBooleanType = USE_LEGACY_BOOLEAN_TYPE_DEFAULT_VALUE.booleanValue();
	private boolean useUnicodeStringTypes;

	private boolean treatDoubleTypedFieldsAsDecimal = TREAT_DOUBLE_TYPED_FIELDS_AS_DECIMAL_DEFAULT_VALUE.booleanValue();

	/*
	 * Tables named "TYPE" need to be quoted
	 */
	private final StandardTableExporter hanaTableExporter = new StandardTableExporter( this ) {

		@Override
		public String[] getSqlCreateStrings(org.hibernate.mapping.Table table, org.hibernate.boot.Metadata metadata) {
			String[] sqlCreateStrings = super.getSqlCreateStrings( table, metadata );
			return quoteTypeIfNecessary( table, sqlCreateStrings, getCreateTableString() );
		}

		@Override
		public String[] getSqlDropStrings(Table table, org.hibernate.boot.Metadata metadata) {
			String[] sqlDropStrings = super.getSqlDropStrings( table, metadata );
			return quoteTypeIfNecessary( table, sqlDropStrings, "drop table" );
		}

		private String[] quoteTypeIfNecessary(org.hibernate.mapping.Table table, String[] strings, String prefix) {
			if ( table.getNameIdentifier() == null || table.getNameIdentifier().isQuoted()
					|| !"type".equals( table.getNameIdentifier().getText().toLowerCase() ) ) {
				return strings;
			}

			Pattern createTableTypePattern = Pattern.compile( "(" + prefix + "\\s+)(" + table.getNameIdentifier().getText() + ")(.+)" );
			Pattern commentOnTableTypePattern = Pattern.compile( "(comment\\s+on\\s+table\\s+)(" + table.getNameIdentifier().getText() + ")(.+)" );
			for ( int i = 0; i < strings.length; i++ ) {
				Matcher createTableTypeMatcher = createTableTypePattern.matcher( strings[i] );
				Matcher commentOnTableTypeMatcher = commentOnTableTypePattern.matcher( strings[i] );
				if ( createTableTypeMatcher.matches() ) {
					strings[i] = createTableTypeMatcher.group( 1 ) + "\"TYPE\"" + createTableTypeMatcher.group( 3 );
				}
				if ( commentOnTableTypeMatcher.matches() ) {
					strings[i] = commentOnTableTypeMatcher.group( 1 ) + "\"TYPE\"" + commentOnTableTypeMatcher.group( 3 );
				}
			}

			return strings;
		}
	};

	public AbstractHANADialect() {
		super();

		this.useUnicodeStringTypes = useUnicodeStringTypesDefault().booleanValue();
		this.clobTypeDescriptor = new HANAClobTypeDescriptor( MAX_LOB_PREFETCH_SIZE_DEFAULT_VALUE,
				useUnicodeStringTypesDefault().booleanValue() );

		registerColumnType( Types.DECIMAL, "decimal($p, $s)" );
		//there is no 'numeric' type in HANA
		registerColumnType( Types.NUMERIC, "decimal($p, $s)" );

		//'double precision' syntax not supported
		registerColumnType( Types.DOUBLE, "double" );

		//no explicit precision
		registerColumnType(Types.TIMESTAMP, "timestamp");
		registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "timestamp");

		// varbinary max length 5000
		registerColumnType( Types.BINARY, 5000, "varbinary($l)" );
		registerColumnType( Types.VARBINARY, 5000, "varbinary($l)" );

		// for longer values, map to blob
		registerColumnType( Types.BINARY, "blob" );
		registerColumnType( Types.VARBINARY, "blob" );

		//there is no 'char' or 'nchar' type in HANA
		registerColumnType( Types.CHAR, "varchar($l)" );
		registerColumnType( Types.NCHAR, "nvarchar($l)" );

		registerColumnType( Types.VARCHAR, 5000, "varchar($l)" );
		registerColumnType( Types.NVARCHAR, 5000, "nvarchar($l)" );

		// for longer values map to clob/nclob
		registerColumnType( Types.VARCHAR, "clob" );
		registerColumnType( Types.NVARCHAR, "nclob" );

		// map tinyint to smallint since tinyint is unsigned on HANA
		registerColumnType( Types.TINYINT, "smallint" );

		registerHibernateType( Types.NCLOB, StandardBasicTypes.MATERIALIZED_NCLOB.getName() );
		registerHibernateType( Types.CLOB, StandardBasicTypes.MATERIALIZED_CLOB.getName() );
		registerHibernateType( Types.BLOB, StandardBasicTypes.MATERIALIZED_BLOB.getName() );
		registerHibernateType( Types.NVARCHAR, StandardBasicTypes.NSTRING.getName() );

		registerHanaKeywords();

		// createBlob() and createClob() are not supported by the HANA JDBC driver
		getDefaultProperties().setProperty( AvailableSettings.NON_CONTEXTUAL_LOB_CREATION, "true" );

		// getGeneratedKeys() is not supported by the HANA JDBC driver
		getDefaultProperties().setProperty( AvailableSettings.USE_GET_GENERATED_KEYS, "false" );
	}

	public int getDefaultDecimalPrecision() {
		//the maximum on HANA
		return 34;
	}

	@Override
	public void initializeFunctionRegistry(QueryEngine queryEngine) {
		super.initializeFunctionRegistry( queryEngine );

		queryEngine.getSqmFunctionRegistry().registerBinaryTernaryPattern(
				"locate",
				StandardBasicTypes.INTEGER,
				"locate(?2, ?1)",
				"locate(?2, ?1, ?3)"
		).setArgumentListSignature("(pattern, string[, start])");

		CommonFunctionFactory.ceiling_ceil( queryEngine );
		CommonFunctionFactory.concat_pipeOperator( queryEngine );
		CommonFunctionFactory.trim2( queryEngine );
		CommonFunctionFactory.cot( queryEngine );
		CommonFunctionFactory.cosh( queryEngine );
		CommonFunctionFactory.sinh( queryEngine );
		CommonFunctionFactory.tanh( queryEngine );
		CommonFunctionFactory.bitand( queryEngine );
		CommonFunctionFactory.hourMinuteSecond( queryEngine );
		CommonFunctionFactory.yearMonthDay( queryEngine );
		CommonFunctionFactory.dayofweekmonthyear( queryEngine );
		CommonFunctionFactory.weekQuarter( queryEngine );
		CommonFunctionFactory.daynameMonthname( queryEngine );
		CommonFunctionFactory.lastDay( queryEngine );
		CommonFunctionFactory.characterLength_length( queryEngine, SqlAstNodeRenderingMode.DEFAULT );
		CommonFunctionFactory.ascii( queryEngine );
		CommonFunctionFactory.chr_char( queryEngine );
		CommonFunctionFactory.addYearsMonthsDaysHoursMinutesSeconds( queryEngine );
		CommonFunctionFactory.daysBetween( queryEngine );
		CommonFunctionFactory.secondsBetween( queryEngine );
		CommonFunctionFactory.format_toVarchar( queryEngine );
		CommonFunctionFactory.currentUtcdatetimetimestamp( queryEngine );
	}

	@Override
	public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
		return new StandardSqlAstTranslatorFactory() {
			@Override
			protected <T extends JdbcOperation> SqlAstTranslator<T> buildTranslator(
					SessionFactoryImplementor sessionFactory, org.hibernate.sql.ast.tree.Statement statement) {
				return new HANASqlAstTranslator<>( sessionFactory, statement );
			}
		};
	}

	/**
	 * HANA has no extract() function, but we can emulate
	 * it using the appropriate named functions instead of
	 * extract().
	 *
	 * The supported fields are
	 * {@link TemporalUnit#YEAR},
	 * {@link TemporalUnit#MONTH}
	 * {@link TemporalUnit#DAY},
	 * {@link TemporalUnit#HOUR},
	 * {@link TemporalUnit#MINUTE},
	 * {@link TemporalUnit#SECOND}
	 * {@link TemporalUnit#WEEK},
	 * {@link TemporalUnit#DAY_OF_WEEK},
	 * {@link TemporalUnit#DAY_OF_MONTH},
	 * {@link TemporalUnit#DAY_OF_YEAR}.
	 */
	@Override
	public String extractPattern(TemporalUnit unit) {
		switch (unit) {
			case DAY_OF_WEEK:
				return "(mod(weekday(?2)+1,7)+1)";
			case DAY:
			case DAY_OF_MONTH:
				return "dayofmonth(?2)";
			case DAY_OF_YEAR:
				return "dayofyear(?2)";
			default:
				//I think week() returns the ISO week number
				return "?1(?2)";
		}
	}

	@Override
	public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
		return (sqlException, message, sql) -> {
			final int errorCode = JdbcExceptionHelper.extractErrorCode( sqlException );

			if ( errorCode == 131 ) {
				// 131 - Transaction rolled back by lock wait timeout
				return new LockTimeoutException( message, sqlException, sql );
			}

			if ( errorCode == 146 ) {
				// 146 - Resource busy and acquire with NOWAIT specified
				return new LockTimeoutException( message, sqlException, sql );
			}

			if ( errorCode == 132 ) {
				// 132 - Transaction rolled back due to unavailable resource
				return new LockAcquisitionException( message, sqlException, sql );
			}

			if ( errorCode == 133 ) {
				// 133 - Transaction rolled back by detected deadlock
				return new LockAcquisitionException( message, sqlException, sql );
			}

			// 259 - Invalid table name
			// 260 - Invalid column name
			// 261 - Invalid index name
			// 262 - Invalid query name
			// 263 - Invalid alias name
			if ( errorCode == 257 || ( errorCode >= 259 && errorCode <= 263 ) ) {
				throw new SQLGrammarException( message, sqlException, sql );
			}

			// 257 - Cannot insert NULL or update to NULL
			// 301 - Unique constraint violated
			// 461 - foreign key constraint violation
			// 462 - failed on update or delete by foreign key constraint violation
			if ( errorCode == 287 || errorCode == 301 || errorCode == 461 || errorCode == 462 ) {
				final String constraintName = getViolatedConstraintNameExtractor()
						.extractConstraintName( sqlException );

				return new ConstraintViolationException( message, sqlException, sql, constraintName );
			}

			return null;
		};
	}

	@Override
	public boolean forUpdateOfColumns() {
		return true;
	}

	@Override
	public RowLockStrategy getWriteRowLockStrategy() {
		return RowLockStrategy.COLUMN;
	}

	@Override
	public String getAddColumnString() {
		return "add (";
	}

	@Override
	public String getAddColumnSuffixString() {
		return ")";
	}

	@Override
	public String getCascadeConstraintsString() {
		return " cascade";
	}

	@Override
	public String getCurrentTimestampSelectString() {
		return "select current_timestamp from sys.dummy";
	}

	@Override
	public String getForUpdateString(final String aliases) {
		return getForUpdateString() + " of " + aliases;
	}

	@Override
	public String getForUpdateString(final String aliases, final LockOptions lockOptions) {
		LockMode lockMode = lockOptions.findGreatestLockMode();
		lockOptions.setLockMode( lockMode );

		// not sure why this is sometimes empty
		if ( aliases == null || aliases.isEmpty() ) {
			return getForUpdateString( lockOptions );
		}

		return getForUpdateString( aliases, lockMode, lockOptions.getTimeOut() );
	}

	@SuppressWarnings({ "deprecation" })
	private String getForUpdateString(String aliases, LockMode lockMode, int timeout) {
		switch ( lockMode ) {
			case UPGRADE:
				return getForUpdateString( aliases );
			case PESSIMISTIC_READ:
				return getReadLockString( aliases, timeout );
			case PESSIMISTIC_WRITE:
				return getWriteLockString( aliases, timeout );
			case UPGRADE_NOWAIT:
			case FORCE:
			case PESSIMISTIC_FORCE_INCREMENT:
				return getForUpdateNowaitString( aliases );
			case UPGRADE_SKIPLOCKED:
				return getForUpdateSkipLockedString( aliases );
			default:
				return "";
		}
	}

	@Override
	public String getForUpdateNowaitString() {
		return getForUpdateString() + " nowait";
	}

	@Override
	public String getNotExpression(final String expression) {
		return "not (" + expression + ")";
	}

	@Override
	public String getQuerySequencesString() {
		return "select * from sys.sequences";
	}

	@Override
	public SequenceInformationExtractor getSequenceInformationExtractor() {
		return SequenceInformationExtractorHANADatabaseImpl.INSTANCE;
	}

	@Override
	protected JdbcTypeDescriptor getSqlTypeDescriptorOverride(final int sqlCode) {
		switch ( sqlCode ) {
			case Types.CLOB:
				return this.clobTypeDescriptor;
			case Types.NCLOB:
				return this.nClobTypeDescriptor;
			case Types.BLOB:
				return this.blobTypeDescriptor;
			case Types.TINYINT:
				// tinyint is unsigned on HANA
				return SmallIntTypeDescriptor.INSTANCE;
			case Types.VARCHAR:
				return this.isUseUnicodeStringTypes() ? NVarcharTypeDescriptor.INSTANCE : VarcharTypeDescriptor.INSTANCE;
			case Types.CHAR:
				return this.isUseUnicodeStringTypes() ? NCharTypeDescriptor.INSTANCE : CharTypeDescriptor.INSTANCE;
			case Types.DOUBLE:
				return this.treatDoubleTypedFieldsAsDecimal ? DecimalTypeDescriptor.INSTANCE : DoubleTypeDescriptor.INSTANCE;
			default:
				return super.getSqlTypeDescriptorOverride( sqlCode );
		}
	}

	@Override
	public boolean isCurrentTimestampSelectStringCallable() {
		return false;
	}

	protected void registerHanaKeywords() {
		registerKeyword( "all" );
		registerKeyword( "alter" );
		registerKeyword( "as" );
		registerKeyword( "before" );
		registerKeyword( "begin" );
		registerKeyword( "both" );
		registerKeyword( "case" );
		registerKeyword( "char" );
		registerKeyword( "condition" );
		registerKeyword( "connect" );
		registerKeyword( "cross" );
		registerKeyword( "cube" );
		registerKeyword( "current_connection" );
		registerKeyword( "current_date" );
		registerKeyword( "current_schema" );
		registerKeyword( "current_time" );
		registerKeyword( "current_timestamp" );
		registerKeyword( "current_transaction_isolation_level" );
		registerKeyword( "current_user" );
		registerKeyword( "current_utcdate" );
		registerKeyword( "current_utctime" );
		registerKeyword( "current_utctimestamp" );
		registerKeyword( "currval" );
		registerKeyword( "cursor" );
		registerKeyword( "declare" );
		registerKeyword( "deferred" );
		registerKeyword( "distinct" );
		registerKeyword( "else" );
		registerKeyword( "elseif" );
		registerKeyword( "end" );
		registerKeyword( "except" );
		registerKeyword( "exception" );
		registerKeyword( "exec" );
		registerKeyword( "false" );
		registerKeyword( "for" );
		registerKeyword( "from" );
		registerKeyword( "full" );
		registerKeyword( "group" );
		registerKeyword( "having" );
		registerKeyword( "if" );
		registerKeyword( "in" );
		registerKeyword( "inner" );
		registerKeyword( "inout" );
		registerKeyword( "intersect" );
		registerKeyword( "into" );
		registerKeyword( "is" );
		registerKeyword( "join" );
		registerKeyword( "leading" );
		registerKeyword( "left" );
		registerKeyword( "limit" );
		registerKeyword( "loop" );
		registerKeyword( "minus" );
		registerKeyword( "natural" );
		registerKeyword( "nchar" );
		registerKeyword( "nextval" );
		registerKeyword( "null" );
		registerKeyword( "on" );
		registerKeyword( "order" );
		registerKeyword( "out" );
		registerKeyword( "prior" );
		registerKeyword( "return" );
		registerKeyword( "returns" );
		registerKeyword( "reverse" );
		registerKeyword( "right" );
		registerKeyword( "rollup" );
		registerKeyword( "rowid" );
		registerKeyword( "select" );
		registerKeyword( "session_user" );
		registerKeyword( "set" );
		registerKeyword( "sql" );
		registerKeyword( "start" );
		registerKeyword( "sysuuid" );
		registerKeyword( "tablesample" );
		registerKeyword( "top" );
		registerKeyword( "trailing" );
		registerKeyword( "true" );
		registerKeyword( "union" );
		registerKeyword( "unknown" );
		registerKeyword( "using" );
		registerKeyword( "utctimestamp" );
		registerKeyword( "values" );
		registerKeyword( "when" );
		registerKeyword( "where" );
		registerKeyword( "while" );
		registerKeyword( "with" );
	}

	@Override
	public ScrollMode defaultScrollMode() {
		return ScrollMode.FORWARD_ONLY;
	}

	/**
	 * HANA currently does not support check constraints.
	 */
	@Override
	public boolean supportsColumnCheck() {
		return false;
	}

	@Override
	public boolean supportsCurrentTimestampSelection() {
		return true;
	}

	@Override
	public boolean supportsEmptyInList() {
		return false;
	}

	@Override
	public boolean supportsExistsInSelect() {
		return false;
	}

	@Override
	public boolean supportsExpectedLobUsagePattern() {
		// http://scn.sap.com/thread/3221812
		return false;
	}

	@Override
	public boolean supportsUnboundedLobLocatorMaterialization() {
		return false;
	}

	@Override
	public SequenceSupport getSequenceSupport() {
		return HANASequenceSupport.INSTANCE;
	}

	@Override
	public boolean supportsTableCheck() {
		return true;
	}

	@Override
	public boolean supportsTupleDistinctCounts() {
		return true;
	}

	@Override
	public boolean dropConstraints() {
		return false;
	}

	@Override
	public boolean supportsRowValueConstructorSyntax() {
		return true;
	}

	@Override
	public boolean supportsRowValueConstructorSyntaxInInList() {
		return true;
	}

	@Override
	public int getMaxAliasLength() {
		return 128;
	}

	@Override
	public LimitHandler getLimitHandler() {
		return LimitOffsetLimitHandler.INSTANCE;
	}

	@Override
	public String getSelectGUIDString() {
		return "select sysuuid from sys.dummy";
	}

	@Override
	public NameQualifierSupport getNameQualifierSupport() {
		return NameQualifierSupport.SCHEMA;
	}

	@SuppressWarnings("deprecation")
	@Override
	public IdentifierHelper buildIdentifierHelper(IdentifierHelperBuilder builder, DatabaseMetaData dbMetaData)
			throws SQLException {
		/*
		 * Copied from Dialect
		 */
		builder.applyIdentifierCasing( dbMetaData );

		builder.applyReservedWords( dbMetaData );
		builder.applyReservedWords( AnsiSqlKeywords.INSTANCE.sql2003() );
		builder.applyReservedWords( getKeywords() );

		builder.setNameQualifierSupport( getNameQualifierSupport() );

		/*
		 * HANA-specific extensions
		 */
		builder.setQuotedCaseStrategy( IdentifierCaseStrategy.MIXED );
		builder.setUnquotedCaseStrategy( IdentifierCaseStrategy.UPPER );

		final IdentifierHelper identifierHelper = builder.build();

		return new IdentifierHelper() {

			private final IdentifierHelper helper = identifierHelper;

			@Override
			public String toMetaDataSchemaName(Identifier schemaIdentifier) {
				return this.helper.toMetaDataSchemaName( schemaIdentifier );
			}

			@Override
			public String toMetaDataObjectName(Identifier identifier) {
				return this.helper.toMetaDataObjectName( identifier );
			}

			@Override
			public String toMetaDataCatalogName(Identifier catalogIdentifier) {
				return this.helper.toMetaDataCatalogName( catalogIdentifier );
			}

			@Override
			public Identifier toIdentifier(String text) {
				return normalizeQuoting( Identifier.toIdentifier( text ) );
			}

			@Override
			public Identifier toIdentifier(String text, boolean quoted) {
				return normalizeQuoting( Identifier.toIdentifier( text, quoted ) );
			}

			@Override
			public Identifier normalizeQuoting(Identifier identifier) {
				Identifier normalizedIdentifier = this.helper.normalizeQuoting( identifier );

				if ( normalizedIdentifier == null ) {
					return null;
				}

				// need to quote names containing special characters like ':'
				if ( !normalizedIdentifier.isQuoted() && !normalizedIdentifier.getText().matches( "\\w+" ) ) {
					normalizedIdentifier = Identifier.quote( normalizedIdentifier );
				}

				return normalizedIdentifier;
			}

			@Override
			public boolean isReservedWord(String word) {
				return this.helper.isReservedWord( word );
			}

			@Override
			public Identifier applyGlobalQuoting(String text) {
				return this.helper.applyGlobalQuoting( text );
			}
		};
	}

	@Override
	public String getCurrentSchemaCommand() {
		return "select current_schema from sys.dummy";
	}

	@Override
	public String getForUpdateNowaitString(String aliases) {
		return getForUpdateString( aliases ) + " nowait";
	}

	@Override
	public String getReadLockString(int timeout) {
		return getWriteLockString( timeout );
	}

	@Override
	public String getReadLockString(String aliases, int timeout) {
		return getWriteLockString( aliases, timeout );
	}

	@Override
	public String getWriteLockString(int timeout) {
		long timeoutInSeconds = getLockWaitTimeoutInSeconds( timeout );
		if ( timeoutInSeconds > 0 ) {
			return getForUpdateString() + " wait " + timeoutInSeconds;
		}
		else if ( timeoutInSeconds == 0 ) {
			return getForUpdateNowaitString();
		}
		else {
			return getForUpdateString();
		}
	}

	@Override
	public String getWriteLockString(String aliases, int timeout) {
		if ( timeout > 0 ) {
			return getForUpdateString( aliases ) + " wait " + getLockWaitTimeoutInSeconds( timeout );
		}
		else if ( timeout == 0 ) {
			return getForUpdateNowaitString( aliases );
		}
		else {
			return getForUpdateString( aliases );
		}
	}

	private long getLockWaitTimeoutInSeconds(int timeoutInMilliseconds) {
		Duration duration = Duration.ofMillis( timeoutInMilliseconds );
		long timeoutInSeconds = duration.getSeconds();
		if ( duration.getNano() != 0 && LOG.isInfoEnabled() ) {
			LOG.info( "Changing the query timeout from " + timeoutInMilliseconds + " ms to " + timeoutInSeconds
					+ " s, because HANA requires the timeout in seconds" );
		}
		return timeoutInSeconds;
	}

	@Override
	public String getFromDual() {
		return "from sys.dummy";
	}

	@Override
	public boolean supportsSelectQueryWithoutFromClause() {
		return false;
	}

	@Override
	public String getQueryHintString(String query, List<String> hints) {
		return query + " with hint (" + String.join( ",", hints ) + ")";
	}

	@Override
	public String getTableComment(String comment) {
		return "comment '" + comment + "'";
	}

	@Override
	public String getColumnComment(String comment) {
		return "comment '" + comment + "'";
	}

	@Override
	public boolean supportsCommentOn() {
		return true;
	}

	@Override
	public boolean supportsPartitionBy() {
		return true;
	}

	@Override
	public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.contributeTypes( typeContributions, serviceRegistry );

		final ConnectionProvider connectionProvider = serviceRegistry.getService( ConnectionProvider.class );

		int maxLobPrefetchSizeDefault = MAX_LOB_PREFETCH_SIZE_DEFAULT_VALUE;
		if ( connectionProvider != null ) {
			Connection conn = null;
			try {
				conn = connectionProvider.getConnection();
				try ( Statement statement = conn.createStatement() ) {
					try ( ResultSet rs = statement.executeQuery(
							"SELECT TOP 1 VALUE, MAP(LAYER_NAME, 'DEFAULT', 1, 'SYSTEM', 2, 'DATABASE', 3, 4) AS LAYER FROM SYS.M_INIFILE_CONTENTS WHERE FILE_NAME='indexserver.ini' AND SECTION='session' AND KEY='max_lob_prefetch_size' ORDER BY LAYER DESC" ) ) {
						// This only works if the current user has the privilege INIFILE ADMIN
						if ( rs.next() ) {
							maxLobPrefetchSizeDefault = rs.getInt( 1 );
						}
					}
				}
			}
			catch (Exception e) {
				LOG.debug(
						"An error occurred while trying to determine the value of the HANA parameter indexserver.ini / session / max_lob_prefetch_size. Using the default value "
								+ maxLobPrefetchSizeDefault,
						e );
			}
			finally {
				if ( conn != null ) {
					try {
						connectionProvider.closeConnection( conn );
					}
					catch (SQLException e) {
						// ignore
					}
				}
			}
		}

		final ConfigurationService configurationService = serviceRegistry.getService( ConfigurationService.class );
		int maxLobPrefetchSize = configurationService.getSetting(
				MAX_LOB_PREFETCH_SIZE_PARAMETER_NAME,
				new Converter<Integer>() {

					@Override
					public Integer convert(Object value) {
						return Integer.valueOf( value.toString() );
					}

				},
				Integer.valueOf( maxLobPrefetchSizeDefault ) ).intValue();

		if ( this.nClobTypeDescriptor.getMaxLobPrefetchSize() != maxLobPrefetchSize ) {
			this.nClobTypeDescriptor = new HANANClobTypeDescriptor( maxLobPrefetchSize );
		}

		if ( this.blobTypeDescriptor.getMaxLobPrefetchSize() != maxLobPrefetchSize ) {
			this.blobTypeDescriptor = new HANABlobTypeDescriptor( maxLobPrefetchSize );
		}

		if ( supportsAsciiStringTypes() ) {
			this.useUnicodeStringTypes = configurationService.getSetting(
					USE_UNICODE_STRING_TYPES_PARAMETER_NAME,
					StandardConverters.BOOLEAN,
					useUnicodeStringTypesDefault()
			).booleanValue();

			if ( this.isUseUnicodeStringTypes() ) {
				registerColumnType( Types.CHAR, "nvarchar($l)" );
				registerColumnType( Types.VARCHAR, 5000, "nvarchar($l)" );

				// for longer values map to clob/nclob
				registerColumnType( Types.VARCHAR, "nclob" );
				registerColumnType( Types.CLOB, "nclob" );
			}

			if ( this.clobTypeDescriptor.getMaxLobPrefetchSize() != maxLobPrefetchSize
					|| this.clobTypeDescriptor.isUseUnicodeStringTypes() != this.useUnicodeStringTypes ) {
				this.clobTypeDescriptor = new HANAClobTypeDescriptor( maxLobPrefetchSize, this.useUnicodeStringTypes );
			}
		}

		this.useLegacyBooleanType = configurationService.getSetting( USE_LEGACY_BOOLEAN_TYPE_PARAMETER_NAME, StandardConverters.BOOLEAN,
				USE_LEGACY_BOOLEAN_TYPE_DEFAULT_VALUE ).booleanValue();

		if ( this.useLegacyBooleanType ) {
			registerColumnType( Types.BOOLEAN, "tinyint" );
		}

		this.treatDoubleTypedFieldsAsDecimal = configurationService.getSetting( TREAT_DOUBLE_TYPED_FIELDS_AS_DECIMAL_PARAMETER_NAME, StandardConverters.BOOLEAN,
				TREAT_DOUBLE_TYPED_FIELDS_AS_DECIMAL_DEFAULT_VALUE ).booleanValue();

		if ( this.treatDoubleTypedFieldsAsDecimal ) {
			registerHibernateType( Types.FLOAT, StandardBasicTypes.BIG_DECIMAL.getName() );
			registerHibernateType( Types.REAL, StandardBasicTypes.BIG_DECIMAL.getName() );
			registerHibernateType( Types.DOUBLE, StandardBasicTypes.BIG_DECIMAL.getName() );
			typeContributions.getTypeConfiguration().getBasicTypeRegistry()
					.register(
							new StandardBasicTypeImpl<>(
									org.hibernate.type.descriptor.java.DoubleTypeDescriptor.INSTANCE,
									NumericTypeDescriptor.INSTANCE
							),
							Double.class.getName()
					);
			typeContributions.getTypeConfiguration().getJdbcToHibernateTypeContributionMap()
					.computeIfAbsent( Types.FLOAT, code -> new HashSet<>() )
					.clear();
			typeContributions.getTypeConfiguration().getJdbcToHibernateTypeContributionMap()
					.computeIfAbsent( Types.REAL, code -> new HashSet<>() )
					.clear();
			typeContributions.getTypeConfiguration().getJdbcToHibernateTypeContributionMap()
					.computeIfAbsent( Types.DOUBLE, code -> new HashSet<>() )
					.clear();
			typeContributions.getTypeConfiguration().getJdbcToHibernateTypeContributionMap()
					.get( Types.FLOAT )
					.add( StandardBasicTypes.BIG_DECIMAL.getName() );
			typeContributions.getTypeConfiguration().getJdbcToHibernateTypeContributionMap()
					.get( Types.REAL )
					.add( StandardBasicTypes.BIG_DECIMAL.getName() );
			typeContributions.getTypeConfiguration().getJdbcToHibernateTypeContributionMap()
					.get( Types.DOUBLE )
					.add( StandardBasicTypes.BIG_DECIMAL.getName() );
			typeContributions.getTypeConfiguration().getJdbcTypeDescriptorRegistry().addDescriptor(
					Types.FLOAT,
					NumericTypeDescriptor.INSTANCE
			);
			typeContributions.getTypeConfiguration().getJdbcTypeDescriptorRegistry().addDescriptor(
					Types.REAL,
					NumericTypeDescriptor.INSTANCE
			);
			typeContributions.getTypeConfiguration().getJdbcTypeDescriptorRegistry().addDescriptor(
					Types.DOUBLE,
					NumericTypeDescriptor.INSTANCE
			);
		}
	}

	public JdbcTypeDescriptor getBlobTypeDescriptor() {
		return this.blobTypeDescriptor;
	}

	@Override
	public String toBooleanValueString(boolean bool) {
		return this.useLegacyBooleanType
				? super.toBooleanValueString( bool )
				: String.valueOf( bool );
	}

	@Override
	public IdentityColumnSupport getIdentityColumnSupport() {
		return new HANAIdentityColumnSupport();
	}

	@Override
	public Exporter<Table> getTableExporter() {
		return this.hanaTableExporter;
	}

	/*
	 * HANA doesn't really support REF_CURSOR returns from a procedure, but REF_CURSOR support can be emulated by using
	 * procedures or functions with an OUT parameter of type TABLE. The results will be returned as result sets on the
	 * callable statement.
	 */
	@Override
	public CallableStatementSupport getCallableStatementSupport() {
		return StandardCallableStatementSupport.REF_CURSOR_INSTANCE;
	}

	@Override
	public int registerResultSetOutParameter(CallableStatement statement, int position) throws SQLException {
		// Result set (TABLE) OUT parameters don't need to be registered
		return position;
	}

	@Override
	public int registerResultSetOutParameter(CallableStatement statement, String name) throws SQLException {
		// Result set (TABLE) OUT parameters don't need to be registered
		return 0;
	}

	@Override
	public boolean supportsOffsetInSubquery() {
		return true;
	}

	@Override
	public boolean supportsWindowFunctions() {
		return true;
	}

	@Override
	public boolean supportsNoWait() {
		return true;
	}

	@Override
	public boolean supportsJdbcConnectionLobCreation(DatabaseMetaData databaseMetaData) {
		return false;
	}

	@Override
	public boolean supportsNoColumnsInsert() {
		return false;
	}

	@Override
	public boolean supportsOrderByInSubquery() {
		return false;
	}

	@Override
	public NullOrdering getNullOrdering() {
		return NullOrdering.SMALLEST;
	}

	@Override
	public String translateDatetimeFormat(String format) {
		//I don't think HANA needs FM
		return OracleDialect.datetimeFormat( format, false, false ).result();
	}

	public boolean isUseUnicodeStringTypes() {
		return this.useUnicodeStringTypes;
	}

	protected abstract boolean supportsAsciiStringTypes();

	protected abstract Boolean useUnicodeStringTypesDefault();

	@Override
	public GroupByConstantRenderingStrategy getGroupByConstantRenderingStrategy() {
		return GroupByConstantRenderingStrategy.EMPTY_GROUPING;
	}
}
