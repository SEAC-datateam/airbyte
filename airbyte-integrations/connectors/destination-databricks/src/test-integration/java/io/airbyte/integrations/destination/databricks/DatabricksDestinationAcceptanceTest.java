/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.databricks;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.cdk.db.Database;
import io.airbyte.cdk.db.jdbc.JdbcUtils;
import io.airbyte.cdk.integrations.base.JavaBaseConstants;
import io.airbyte.cdk.integrations.destination.StandardNameTransformer;
import io.airbyte.cdk.integrations.destination.jdbc.copy.StreamCopierFactory;
import io.airbyte.cdk.integrations.destination.s3.avro.JsonFieldNameUpdater;
import io.airbyte.cdk.integrations.destination.s3.util.AvroRecordHelper;
import io.airbyte.cdk.integrations.standardtest.destination.DestinationAcceptanceTest;
import io.airbyte.commons.json.Jsons;
import org.jooq.DSLContext;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.asterisk;
import static org.jooq.impl.DSL.field;

public abstract class DatabricksDestinationAcceptanceTest extends DestinationAcceptanceTest {

    private final StandardNameTransformer nameTransformer = new DatabricksNameTransformer();
    protected JsonNode configJson;
    protected DatabricksDestinationConfig databricksConfig;

    @Override
    protected String getImageName() {
        return "airbyte/destination-databricks:dev";
    }

    @Override
    protected JsonNode getConfig() {
        return configJson;
    }

    @Override
    protected List<JsonNode> retrieveRecords(final TestDestinationEnv testEnv,
                                             final String streamName,
                                             final String namespace,
                                             final JsonNode streamSchema)
            throws SQLException {
        final String tableName = nameTransformer.getIdentifier(streamName);
        final String schemaName = StreamCopierFactory.getSchema(namespace, databricksConfig.schema(), nameTransformer);
        final String catalog = databricksConfig.catalog();
        final JsonFieldNameUpdater nameUpdater = AvroRecordHelper.getFieldNameUpdater(streamName, namespace, streamSchema);

        try {
            final DSLContext dslContext = DatabricksUtilTest.getDslContext(databricksConfig);
            final Database database = new Database(dslContext);
            return database.query(ctx -> ctx.select(asterisk())
                    .from(String.format("%s.%s.%s", catalog, schemaName, tableName))
                    .orderBy(field(JavaBaseConstants.COLUMN_NAME_EMITTED_AT).asc())
                    .fetch().stream()
                    .map(record -> {
                        final JsonNode json = Jsons.deserialize(record.formatJSON(JdbcUtils.getDefaultJSONFormat()));
                        final JsonNode jsonWithOriginalFields = nameUpdater.getJsonWithOriginalFieldNames(json);
                        return AvroRecordHelper.pruneAirbyteJson(jsonWithOriginalFields);
                    })
                    .collect(Collectors.toList()));
        } catch (final Exception e) {
            throw new SQLException(e);
        }
    }

    @Override
    protected void tearDown(final TestDestinationEnv testEnv) throws SQLException {
        DatabricksUtilTest.cleanUpData(databricksConfig);
    }

}
