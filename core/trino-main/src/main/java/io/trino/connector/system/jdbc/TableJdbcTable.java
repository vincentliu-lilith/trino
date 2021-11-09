/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.connector.system.jdbc;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slices;
import io.trino.FullConnectorSession;
import io.trino.Session;
import io.trino.connector.system.SystemColumnHandle;
import io.trino.metadata.Metadata;
import io.trino.metadata.QualifiedTablePrefix;
import io.trino.security.AccessControl;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.InMemoryRecordSet;
import io.trino.spi.connector.InMemoryRecordSet.Builder;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.NullableValue;
import io.trino.spi.predicate.TupleDomain;

import javax.inject.Inject;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.connector.system.jdbc.FilterUtil.emptyOrEquals;
import static io.trino.connector.system.jdbc.FilterUtil.tablePrefix;
import static io.trino.connector.system.jdbc.FilterUtil.tryGetSingleVarcharValue;
import static io.trino.metadata.MetadataListing.listCatalogs;
import static io.trino.metadata.MetadataListing.listSchemas;
import static io.trino.metadata.MetadataListing.listTables;
import static io.trino.metadata.MetadataListing.listViews;
import static io.trino.metadata.MetadataUtil.TableMetadataBuilder.tableMetadataBuilder;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class TableJdbcTable
        extends JdbcTable
{
    public static final SchemaTableName NAME = new SchemaTableName("jdbc", "tables");

    private static final int MAX_DOMAIN_SIZE = 100;

    private static final ColumnHandle TABLE_CATALOG_COLUMN = new SystemColumnHandle("table_cat");
    private static final ColumnHandle TABLE_SCHEMA_COLUMN = new SystemColumnHandle("table_schem");
    private static final ColumnHandle TABLE_NAME_COLUMN = new SystemColumnHandle("table_name");
    private static final ColumnHandle TABLE_TYPE_COLUMN = new SystemColumnHandle("table_type");

    public static final ConnectorTableMetadata METADATA = tableMetadataBuilder(NAME)
            .column("table_cat", createUnboundedVarcharType())
            .column("table_schem", createUnboundedVarcharType())
            .column("table_name", createUnboundedVarcharType())
            .column("table_type", createUnboundedVarcharType())
            .column("remarks", createUnboundedVarcharType())
            .column("type_cat", createUnboundedVarcharType())
            .column("type_schem", createUnboundedVarcharType())
            .column("type_name", createUnboundedVarcharType())
            .column("self_referencing_col_name", createUnboundedVarcharType())
            .column("ref_generation", createUnboundedVarcharType())
            .build();

    private final Metadata metadata;
    private final AccessControl accessControl;

    @Inject
    public TableJdbcTable(Metadata metadata, AccessControl accessControl)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
    }

    @Override
    public ConnectorTableMetadata getTableMetadata()
    {
        return METADATA;
    }

    @Override
    public TupleDomain<ColumnHandle> applyFilter(ConnectorSession connectorSession, Constraint constraint)
    {
        TupleDomain<ColumnHandle> tupleDomain = constraint.getSummary();
        if (tupleDomain.isNone() || constraint.predicate().isEmpty()) {
            return tupleDomain;
        }
        Predicate<Map<ColumnHandle, NullableValue>> predicate = constraint.predicate().get();
        Set<ColumnHandle> predicateColumns = constraint.getPredicateColumns().orElseThrow(() -> new VerifyException("columns not present for a predicate"));

        boolean hasSchemaPredicate = predicateColumns.contains(TABLE_SCHEMA_COLUMN);
        boolean hasTablePredicate = predicateColumns.contains(TABLE_NAME_COLUMN);
        if (!hasSchemaPredicate && !hasTablePredicate) {
            // No filter on schema name and table name at all.
            return tupleDomain;
        }

        Session session = ((FullConnectorSession) connectorSession).getSession();

        Optional<String> catalogFilter = tryGetSingleVarcharValue(tupleDomain, TABLE_CATALOG_COLUMN);
        Optional<String> schemaFilter = tryGetSingleVarcharValue(tupleDomain, TABLE_SCHEMA_COLUMN);
        Optional<String> tableFilter = tryGetSingleVarcharValue(tupleDomain, TABLE_NAME_COLUMN);

        if (schemaFilter.isPresent() && tableFilter.isPresent()) {
            // No need to narrow down the domain.
            return tupleDomain;
        }

        List<String> catalogs = listCatalogs(session, metadata, accessControl, catalogFilter).keySet().stream()
                .filter(catalogName -> predicate.test(ImmutableMap.of(TABLE_CATALOG_COLUMN, toNullableValue(catalogName))))
                .collect(toImmutableList());

        List<CatalogSchemaName> schemas = catalogs.stream()
                .flatMap(catalogName ->
                        listSchemas(session, metadata, accessControl, catalogName, schemaFilter).stream()
                                .filter(schemaName -> !hasSchemaPredicate || predicate.test(ImmutableMap.of(
                                        TABLE_CATALOG_COLUMN, toNullableValue(catalogName),
                                        TABLE_SCHEMA_COLUMN, toNullableValue(schemaName))))
                                .map(schemaName -> new CatalogSchemaName(catalogName, schemaName)))
                .collect(toImmutableList());

        Domain tableTypeDomain;
        if (tupleDomain.getDomains().isPresent()) {
            tableTypeDomain = tupleDomain.getDomains().get().getOrDefault(TABLE_TYPE_COLUMN, Domain.all(createUnboundedVarcharType()));
        }
        else {
            tableTypeDomain = Domain.all(createUnboundedVarcharType());
        }

        if (!hasTablePredicate) {
            return TupleDomain.withColumnDomains(ImmutableMap.<ColumnHandle, Domain>builder()
                    .put(TABLE_CATALOG_COLUMN, schemas.stream()
                            .map(CatalogSchemaName::getCatalogName)
                            .collect(toVarcharDomain())
                            .simplify(MAX_DOMAIN_SIZE))
                    .put(TABLE_SCHEMA_COLUMN, schemas.stream()
                            .map(CatalogSchemaName::getSchemaName)
                            .collect(toVarcharDomain())
                            .simplify(MAX_DOMAIN_SIZE))
                    .put(TABLE_NAME_COLUMN, Domain.all(createUnboundedVarcharType()))
                    .put(TABLE_TYPE_COLUMN, tableTypeDomain)
                    .buildOrThrow());
        }

        List<CatalogSchemaTableName> tables = schemas.stream()
                .flatMap(schema -> {
                    QualifiedTablePrefix tablePrefix = tableFilter
                            .map(x -> new QualifiedTablePrefix(schema.getCatalogName(), schema.getSchemaName(), x))
                            .orElse(new QualifiedTablePrefix(schema.getCatalogName(), schema.getSchemaName()));
                    return listTables(session, metadata, accessControl, tablePrefix).stream()
                            .filter(schemaTableName -> predicate.test(ImmutableMap.of(
                                    TABLE_CATALOG_COLUMN, toNullableValue(schema.getCatalogName()),
                                    TABLE_SCHEMA_COLUMN, toNullableValue(schemaTableName.getSchemaName()),
                                    TABLE_NAME_COLUMN, toNullableValue(schemaTableName.getTableName()))))
                            .map(schemaTableName -> new CatalogSchemaTableName(schema.getCatalogName(), schemaTableName.getSchemaName(), schemaTableName.getTableName()));
                })
                .collect(toImmutableList());

        return TupleDomain.withColumnDomains(ImmutableMap.<ColumnHandle, Domain>builder()
                .put(TABLE_CATALOG_COLUMN, tables.stream()
                        .map(CatalogSchemaTableName::getCatalogName)
                        .collect(toVarcharDomain())
                        .simplify(MAX_DOMAIN_SIZE))
                .put(TABLE_SCHEMA_COLUMN, tables.stream()
                        .map(catalogSchemaTableName -> catalogSchemaTableName.getSchemaTableName().getSchemaName())
                        .collect(toVarcharDomain())
                        .simplify(MAX_DOMAIN_SIZE))
                .put(TABLE_NAME_COLUMN, tables.stream()
                        .map(catalogSchemaTableName -> catalogSchemaTableName.getSchemaTableName().getTableName())
                        .collect(toVarcharDomain())
                        .simplify(MAX_DOMAIN_SIZE))
                .put(TABLE_TYPE_COLUMN, tableTypeDomain)
                .buildOrThrow());
    }

    @Override
    public RecordCursor cursor(ConnectorTransactionHandle transactionHandle, ConnectorSession connectorSession, TupleDomain<Integer> constraint)
    {
        Builder table = InMemoryRecordSet.builder(METADATA);
        if (constraint.isNone()) {
            return table.build().cursor();
        }

        Session session = ((FullConnectorSession) connectorSession).getSession();
        Optional<String> catalogFilter = tryGetSingleVarcharValue(constraint, 0);
        Optional<String> schemaFilter = tryGetSingleVarcharValue(constraint, 1);
        Optional<String> tableFilter = tryGetSingleVarcharValue(constraint, 2);
        Optional<String> typeFilter = tryGetSingleVarcharValue(constraint, 3);

        boolean includeTables = emptyOrEquals(typeFilter, "TABLE");
        boolean includeViews = emptyOrEquals(typeFilter, "VIEW");
        if (!includeTables && !includeViews) {
            return table.build().cursor();
        }

        if (isNonLowercase(schemaFilter) || isNonLowercase(tableFilter)) {
            // Non-lowercase predicate will never match a lowercase name (until TODO https://github.com/trinodb/trino/issues/17)
            return table.build().cursor();
        }

        Domain catalogDomain = constraint.getDomains().get().getOrDefault(0, Domain.all(createUnboundedVarcharType()));
        Domain schemaDomain = constraint.getDomains().get().getOrDefault(1, Domain.all(createUnboundedVarcharType()));
        Domain tableDomain = constraint.getDomains().get().getOrDefault(2, Domain.all(createUnboundedVarcharType()));

        for (String catalog : listCatalogs(session, metadata, accessControl, catalogFilter).keySet()) {
            if (!catalogDomain.includesNullableValue(utf8Slice(catalog))) {
                continue;
            }

            if ((schemaDomain.isAll() && tableDomain.isAll()) || schemaFilter.isPresent()) {
                QualifiedTablePrefix prefix = tablePrefix(catalog, schemaFilter, tableFilter);

                Set<SchemaTableName> views = listViews(session, metadata, accessControl, prefix);
                for (SchemaTableName name : listTables(session, metadata, accessControl, prefix)) {
                    boolean isView = views.contains(name);
                    if ((includeTables && !isView) || (includeViews && isView)) {
                        table.addRow(tableRow(catalog, name, isView ? "VIEW" : "TABLE"));
                    }
                }
            }
            else {
                Collection<String> schemas = listSchemas(session, metadata, accessControl, catalog, schemaFilter);
                for (String schema : schemas) {
                    if (!schemaDomain.includesNullableValue(utf8Slice(schema))) {
                        continue;
                    }

                    QualifiedTablePrefix prefix = tableFilter
                            .map(x -> new QualifiedTablePrefix(catalog, schema, x))
                            .orElse(new QualifiedTablePrefix(catalog, schema));
                    Set<SchemaTableName> views = listViews(session, metadata, accessControl, prefix);
                    Set<SchemaTableName> tables = listTables(session, metadata, accessControl, prefix);
                    for (SchemaTableName schemaTableName : tables) {
                        String tableName = schemaTableName.getTableName();
                        if (!tableDomain.includesNullableValue(utf8Slice(tableName))) {
                            continue;
                        }

                        boolean isView = views.contains(schemaTableName);
                        if ((includeTables && !isView) || (includeViews && isView)) {
                            table.addRow(tableRow(catalog, schemaTableName, isView ? "VIEW" : "TABLE"));
                        }
                    }
                }
            }
        }
        return table.build().cursor();
    }

    private static boolean isNonLowercase(Optional<String> filter)
    {
        return filter.filter(value -> !value.equals(value.toLowerCase(ENGLISH))).isPresent();
    }

    private static Object[] tableRow(String catalog, SchemaTableName name, String type)
    {
        return new Object[] {catalog, name.getSchemaName(), name.getTableName(), type,
                null, null, null, null, null, null};
    }

    private static NullableValue toNullableValue(String varcharValue)
    {
        return NullableValue.of(createUnboundedVarcharType(), utf8Slice(varcharValue));
    }

    private static Collector<String, ?, Domain> toVarcharDomain()
    {
        return Collectors.collectingAndThen(toImmutableSet(), set -> {
            if (set.isEmpty()) {
                return Domain.none(createUnboundedVarcharType());
            }
            return Domain.multipleValues(createUnboundedVarcharType(), set.stream()
                    .map(Slices::utf8Slice)
                    .collect(toImmutableList()));
        });
    }
}
