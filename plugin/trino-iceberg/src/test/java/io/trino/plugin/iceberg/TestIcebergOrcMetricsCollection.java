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
package io.trino.plugin.iceberg;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.Session;
import io.trino.plugin.hive.HdfsConfig;
import io.trino.plugin.hive.HdfsConfiguration;
import io.trino.plugin.hive.HdfsConfigurationInitializer;
import io.trino.plugin.hive.HdfsEnvironment;
import io.trino.plugin.hive.HiveHdfsConfiguration;
import io.trino.plugin.hive.NodeVersion;
import io.trino.plugin.hive.authentication.NoHdfsAuthentication;
import io.trino.plugin.hive.metastore.HiveMetastore;
import io.trino.plugin.hive.metastore.MetastoreConfig;
import io.trino.plugin.hive.metastore.file.FileHiveMetastore;
import io.trino.plugin.hive.metastore.file.FileHiveMetastoreConfig;
import io.trino.plugin.tpch.TpchPlugin;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.MaterializedResult;
import io.trino.testing.MaterializedRow;
import io.trino.testing.QueryRunner;
import org.apache.iceberg.FileContent;
import org.testng.annotations.Test;

import java.io.File;
import java.util.Map;
import java.util.Optional;

import static io.trino.SystemSessionProperties.MAX_DRIVERS_PER_TASK;
import static io.trino.SystemSessionProperties.TASK_CONCURRENCY;
import static io.trino.SystemSessionProperties.TASK_WRITER_COUNT;
import static io.trino.plugin.iceberg.TestIcebergOrcMetricsCollection.DataFileRecord.toDataFileRecord;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class TestIcebergOrcMetricsCollection
        extends AbstractTestQueryFramework
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        Session session = testSessionBuilder()
                .setCatalog("iceberg")
                .setSchema("test_schema")
                .setSystemProperty(TASK_CONCURRENCY, "1")
                .setSystemProperty(TASK_WRITER_COUNT, "1")
                .setSystemProperty(MAX_DRIVERS_PER_TASK, "1")
                .setCatalogSessionProperty("iceberg", "orc_string_statistics_limit", Integer.MAX_VALUE + "B")
                .build();
        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session)
                .setNodeCount(1)
                .build();

        File baseDir = queryRunner.getCoordinator().getBaseDataDir().resolve("iceberg_data").toFile();

        HdfsConfig hdfsConfig = new HdfsConfig();
        HdfsConfiguration hdfsConfiguration = new HiveHdfsConfiguration(new HdfsConfigurationInitializer(hdfsConfig), ImmutableSet.of());
        HdfsEnvironment hdfsEnvironment = new HdfsEnvironment(hdfsConfiguration, hdfsConfig, new NoHdfsAuthentication());

        HiveMetastore metastore = new FileHiveMetastore(
                new NodeVersion("test_version"),
                hdfsEnvironment,
                new MetastoreConfig(),
                new FileHiveMetastoreConfig()
                        .setCatalogDirectory(baseDir.toURI().toString())
                        .setMetastoreUser("test"));

        queryRunner.installPlugin(new TestingIcebergPlugin(Optional.of(metastore), Optional.empty()));
        queryRunner.createCatalog("iceberg", "iceberg");

        queryRunner.installPlugin(new TpchPlugin());
        queryRunner.createCatalog("tpch", "tpch");

        queryRunner.execute("CREATE SCHEMA test_schema");

        return queryRunner;
    }

    @Test
    public void testBasic()
    {
        assertUpdate("CREATE TABLE orders WITH (format = 'ORC') AS SELECT * FROM tpch.tiny.orders", 15000);
        MaterializedResult materializedResult = computeActual("SELECT * FROM \"orders$files\"");
        assertEquals(materializedResult.getRowCount(), 1);
        DataFileRecord datafile = toDataFileRecord(materializedResult.getMaterializedRows().get(0));

        // check content
        assertEquals(datafile.getContent(), FileContent.DATA.id());

        // Check file format
        assertEquals(datafile.getFileFormat(), "ORC");

        // Check file row count
        assertEquals(datafile.getRecordCount(), 15000L);

        // Check per-column value count
        datafile.getValueCounts().values().forEach(valueCount -> assertEquals(valueCount, (Long) 15000L));

        // Check per-column null value count
        datafile.getNullValueCounts().values().forEach(nullValueCount -> assertEquals(nullValueCount, (Long) 0L));

        // Check NaN value count
        // TODO: add more checks after NaN info is collected
        assertNull(datafile.getNanValueCounts());

        // Check per-column lower bound
        Map<Integer, String> lowerBounds = datafile.getLowerBounds();
        assertQuery("SELECT min(orderkey) FROM tpch.tiny.orders", "VALUES " + lowerBounds.get(1));
        assertQuery("SELECT min(custkey) FROM tpch.tiny.orders", "VALUES " + lowerBounds.get(2));
        assertQuery("SELECT min(orderstatus) FROM tpch.tiny.orders", "VALUES '" + lowerBounds.get(3) + "'");
        assertQuery("SELECT min(totalprice) FROM tpch.tiny.orders", "VALUES " + lowerBounds.get(4));
        assertQuery("SELECT min(orderdate) FROM tpch.tiny.orders", "VALUES DATE '" + lowerBounds.get(5) + "'");
        assertQuery("SELECT min(orderpriority) FROM tpch.tiny.orders", "VALUES '" + lowerBounds.get(6) + "'");
        assertQuery("SELECT min(clerk) FROM tpch.tiny.orders", "VALUES '" + lowerBounds.get(7) + "'");
        assertQuery("SELECT min(shippriority) FROM tpch.tiny.orders", "VALUES " + lowerBounds.get(8));
        assertQuery("SELECT min(comment) FROM tpch.tiny.orders", "VALUES '" + lowerBounds.get(9) + "'");

        // Check per-column upper bound
        Map<Integer, String> upperBounds = datafile.getUpperBounds();
        assertQuery("SELECT max(orderkey) FROM tpch.tiny.orders", "VALUES " + upperBounds.get(1));
        assertQuery("SELECT max(custkey) FROM tpch.tiny.orders", "VALUES " + upperBounds.get(2));
        assertQuery("SELECT max(orderstatus) FROM tpch.tiny.orders", "VALUES '" + upperBounds.get(3) + "'");
        assertQuery("SELECT max(totalprice) FROM tpch.tiny.orders", "VALUES " + upperBounds.get(4));
        assertQuery("SELECT max(orderdate) FROM tpch.tiny.orders", "VALUES DATE '" + upperBounds.get(5) + "'");
        assertQuery("SELECT max(orderpriority) FROM tpch.tiny.orders", "VALUES '" + upperBounds.get(6) + "'");
        assertQuery("SELECT max(clerk) FROM tpch.tiny.orders", "VALUES '" + upperBounds.get(7) + "'");
        assertQuery("SELECT max(shippriority) FROM tpch.tiny.orders", "VALUES " + upperBounds.get(8));
        assertQuery("SELECT max(comment) FROM tpch.tiny.orders", "VALUES '" + upperBounds.get(9) + "'");

        assertUpdate("DROP TABLE orders");
    }

    @Test
    public void testWithNulls()
    {
        assertUpdate("CREATE TABLE test_with_nulls (_integer INTEGER, _real REAL, _string VARCHAR, _timestamp TIMESTAMP(6))");
        assertUpdate("INSERT INTO test_with_nulls VALUES " +
                "(7, 3.4, 'aaa', TIMESTAMP '2020-01-01 00:00:00.123456')," +
                "(3, 4.5, 'bbb', TIMESTAMP '2021-02-01 00:23:10.398102')," +
                "(4, null, 'ccc', null)," +
                "(null, null, 'ddd', null)", 4);
        MaterializedResult materializedResult = computeActual("SELECT * FROM \"test_with_nulls$files\"");
        assertEquals(materializedResult.getRowCount(), 1);
        DataFileRecord datafile = toDataFileRecord(materializedResult.getMaterializedRows().get(0));

        // Check per-column value count
        datafile.getValueCounts().values().forEach(valueCount -> assertEquals(valueCount, (Long) 4L));

        // Check per-column null value count
        assertEquals(datafile.getNullValueCounts().get(1), (Long) 1L);
        assertEquals(datafile.getNullValueCounts().get(2), (Long) 2L);
        assertEquals(datafile.getNullValueCounts().get(3), (Long) 0L);
        assertEquals(datafile.getNullValueCounts().get(4), (Long) 2L);

        // Check per-column lower bound
        assertEquals(datafile.getLowerBounds().get(1), "3");
        assertEquals(datafile.getLowerBounds().get(2), "3.4");
        assertEquals(datafile.getLowerBounds().get(3), "aaa");
        assertEquals(datafile.getLowerBounds().get(4), "2020-01-01T00:00:00.123");

        assertUpdate("DROP TABLE test_with_nulls");

        assertUpdate("CREATE TABLE test_all_nulls (_integer INTEGER)");
        assertUpdate("INSERT INTO test_all_nulls VALUES null, null, null", 3);
        materializedResult = computeActual("SELECT * FROM \"test_all_nulls$files\"");
        assertEquals(materializedResult.getRowCount(), 1);
        datafile = toDataFileRecord(materializedResult.getMaterializedRows().get(0));

        // Check per-column value count
        assertEquals(datafile.getValueCounts().get(1), (Long) 3L);

        // Check per-column null value count
        assertEquals(datafile.getNullValueCounts().get(1), (Long) 3L);

        // Check that lower bounds and upper bounds are nulls. (There's no non-null record)
        assertNull(datafile.getLowerBounds());
        assertNull(datafile.getUpperBounds());

        assertUpdate("DROP TABLE test_all_nulls");
    }

    @Test
    public void testWithNaNs()
    {
        assertUpdate("CREATE TABLE test_with_nans (_int INTEGER, _real REAL, _double DOUBLE)");
        assertUpdate("INSERT INTO test_with_nans VALUES (1, 1.1, 1.1), (2, nan(), 4.5), (3, 4.6, -nan())", 3);
        MaterializedResult materializedResult = computeActual("SELECT * FROM \"test_with_nans$files\"");
        assertEquals(materializedResult.getRowCount(), 1);
        DataFileRecord datafile = toDataFileRecord(materializedResult.getMaterializedRows().get(0));

        // Check per-column value count
        datafile.getValueCounts().values().forEach(valueCount -> assertEquals(valueCount, (Long) 3L));

        // TODO: add more checks after NaN info is collected
        assertNull(datafile.getNanValueCounts());
        assertNull(datafile.getLowerBounds().get(2));
        assertNull(datafile.getLowerBounds().get(3));
        assertNull(datafile.getUpperBounds().get(2));
        assertNull(datafile.getUpperBounds().get(3));

        assertUpdate("DROP TABLE test_with_nans");
    }

    @Test
    public void testNestedTypes()
    {
        assertUpdate("CREATE TABLE test_nested_types (col1 INTEGER, col2 ROW (f1 INTEGER, f2 ARRAY(INTEGER), f3 DOUBLE))");
        assertUpdate("INSERT INTO test_nested_types VALUES " +
                "(7, ROW(3, ARRAY[10, 11, 19], 1.9)), " +
                "(-9, ROW(4, ARRAY[13, 16, 20], -2.9)), " +
                "(8, ROW(0, ARRAY[14, 17, 21], 3.9)), " +
                "(3, ROW(10, ARRAY[15, 18, 22], 4.9))", 4);
        MaterializedResult materializedResult = computeActual("SELECT * FROM \"test_nested_types$files\"");
        assertEquals(materializedResult.getRowCount(), 1);
        DataFileRecord datafile = toDataFileRecord(materializedResult.getMaterializedRows().get(0));

        Map<Integer, String> lowerBounds = datafile.getLowerBounds();
        Map<Integer, String> upperBounds = datafile.getUpperBounds();

        // Only
        // 1. top-level primitive columns
        // 2. and nested primitive fields that are not descendants of LISTs or MAPs
        // should appear in lowerBounds or UpperBounds
        assertEquals(lowerBounds.size(), 3);
        assertEquals(upperBounds.size(), 3);

        // col1
        assertEquals(lowerBounds.get(1), "-9");
        assertEquals(upperBounds.get(1), "8");

        // col2.f1 (key in lowerBounds/upperBounds is Iceberg ID)
        assertEquals(lowerBounds.get(3), "0");
        assertEquals(upperBounds.get(3), "10");

        // col2.f3 (key in lowerBounds/upperBounds is Iceberg ID)
        assertEquals(lowerBounds.get(5), "-2.9");
        assertEquals(upperBounds.get(5), "4.9");

        assertUpdate("DROP TABLE test_nested_types");
    }

    @Test
    public void testWithTimestamps()
    {
        assertUpdate("CREATE TABLE test_timestamp (_timestamp TIMESTAMP(6)) WITH (format = 'ORC')");
        assertUpdate("INSERT INTO test_timestamp VALUES" +
                "(TIMESTAMP '2021-01-01 00:00:00.111111'), " +
                "(TIMESTAMP '2021-01-01 00:00:00.222222'), " +
                "(TIMESTAMP '2021-01-31 00:00:00.333333')", 3);
        MaterializedResult materializedResult = computeActual("SELECT * FROM \"test_timestamp$files\"");
        assertEquals(materializedResult.getRowCount(), 1);
        DataFileRecord datafile = toDataFileRecord(materializedResult.getMaterializedRows().get(0));

        // Check file format
        assertEquals(datafile.getFileFormat(), "ORC");

        // Check file row count
        assertEquals(datafile.getRecordCount(), 3L);

        // Check per-column value count
        datafile.getValueCounts().values().forEach(valueCount -> assertEquals(valueCount, (Long) 3L));

        // Check per-column null value count
        datafile.getNullValueCounts().values().forEach(nullValueCount -> assertEquals(nullValueCount, (Long) 0L));

        // Check column lower bound. Min timestamp doesn't rely on file-level statistics and will not be truncated to milliseconds.
        assertEquals(datafile.getLowerBounds().get(1), "2021-01-01T00:00:00.111");
        assertQuery("SELECT min(_timestamp) FROM test_timestamp", "VALUES '2021-01-01 00:00:00.111111'");

        // Check column upper bound. Max timestamp doesn't rely on file-level statistics and will not be truncated to milliseconds.
        assertEquals(datafile.getUpperBounds().get(1), "2021-01-31T00:00:00.333999");
        assertQuery("SELECT max(_timestamp) FROM test_timestamp", "VALUES '2021-01-31 00:00:00.333333'");

        assertUpdate("DROP TABLE test_timestamp");
    }

    public static class DataFileRecord
    {
        private final int content;
        private final String filePath;
        private final String fileFormat;
        private final long recordCount;
        private final long fileSizeInBytes;
        private final Map<Integer, Long> columnSizes;
        private final Map<Integer, Long> valueCounts;
        private final Map<Integer, Long> nullValueCounts;
        private final Map<Integer, Long> nanValueCounts;
        private final Map<Integer, String> lowerBounds;
        private final Map<Integer, String> upperBounds;

        public static DataFileRecord toDataFileRecord(MaterializedRow row)
        {
            assertEquals(row.getFieldCount(), 14);
            return new DataFileRecord(
                    (int) row.getField(0),
                    (String) row.getField(1),
                    (String) row.getField(2),
                    (long) row.getField(3),
                    (long) row.getField(4),
                    row.getField(5) != null ? ImmutableMap.copyOf((Map<Integer, Long>) row.getField(5)) : null,
                    row.getField(6) != null ? ImmutableMap.copyOf((Map<Integer, Long>) row.getField(6)) : null,
                    row.getField(7) != null ? ImmutableMap.copyOf((Map<Integer, Long>) row.getField(7)) : null,
                    row.getField(8) != null ? ImmutableMap.copyOf((Map<Integer, Long>) row.getField(8)) : null,
                    row.getField(9) != null ? ImmutableMap.copyOf((Map<Integer, String>) row.getField(9)) : null,
                    row.getField(10) != null ? ImmutableMap.copyOf((Map<Integer, String>) row.getField(10)) : null);
        }

        private DataFileRecord(
                int content,
                String filePath,
                String fileFormat,
                long recordCount,
                long fileSizeInBytes,
                Map<Integer, Long> columnSizes,
                Map<Integer, Long> valueCounts,
                Map<Integer, Long> nullValueCounts,
                Map<Integer, Long> nanValueCounts,
                Map<Integer, String> lowerBounds,
                Map<Integer, String> upperBounds)
        {
            this.content = content;
            this.filePath = filePath;
            this.fileFormat = fileFormat;
            this.recordCount = recordCount;
            this.fileSizeInBytes = fileSizeInBytes;
            this.columnSizes = columnSizes;
            this.valueCounts = valueCounts;
            this.nullValueCounts = nullValueCounts;
            this.nanValueCounts = nanValueCounts;
            this.lowerBounds = lowerBounds;
            this.upperBounds = upperBounds;
        }

        public int getContent()
        {
            return content;
        }

        public String getFilePath()
        {
            return filePath;
        }

        public String getFileFormat()
        {
            return fileFormat;
        }

        public long getRecordCount()
        {
            return recordCount;
        }

        public long getFileSizeInBytes()
        {
            return fileSizeInBytes;
        }

        public Map<Integer, Long> getColumnSizes()
        {
            return columnSizes;
        }

        public Map<Integer, Long> getValueCounts()
        {
            return valueCounts;
        }

        public Map<Integer, Long> getNullValueCounts()
        {
            return nullValueCounts;
        }

        public Map<Integer, Long> getNanValueCounts()
        {
            return nanValueCounts;
        }

        public Map<Integer, String> getLowerBounds()
        {
            return lowerBounds;
        }

        public Map<Integer, String> getUpperBounds()
        {
            return upperBounds;
        }
    }
}
