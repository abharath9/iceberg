/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.flink.source;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.avro.generic.GenericRecord;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.formats.avro.typeutils.GenericRecordAvroTypeInfo;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.types.Row;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.ParameterizedTestExtension;
import org.apache.iceberg.Parameters;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.avro.AvroSchemaUtil;
import org.apache.iceberg.data.RandomGenericData;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.flink.FlinkSchemaUtil;
import org.apache.iceberg.flink.TestFixtures;
import org.apache.iceberg.flink.TestHelpers;
import org.apache.iceberg.flink.data.RowDataToRowMapper;
import org.apache.iceberg.flink.sink.AvroGenericRecordToRowDataMapper;
import org.apache.iceberg.flink.source.reader.AvroGenericRecordConverter;
import org.apache.iceberg.flink.source.reader.AvroGenericRecordReaderFunction;
import org.apache.iceberg.flink.source.reader.ReaderFunction;
import org.apache.iceberg.flink.source.reader.RowDataConverter;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.types.TypeUtil;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.flink.configuration.Configuration;

@ExtendWith(ParameterizedTestExtension.class)
public class TestIcebergSourceBoundedGenericRecord extends TestIcebergSourceBoundedConverterBase<GenericRecord> {

  @Parameters(name = "format={0}, parallelism = {1}, useConverter = {2}")
  public static Object[][] parameters() {
    return new Object[][]{
      {FileFormat.AVRO, 2, true},
      {FileFormat.PARQUET, 2, true},
      {FileFormat.PARQUET, 2, false},
      {FileFormat.ORC, 2, true}
    };
  }

  @TestTemplate
  public void testUnpartitionedTable() throws Exception {
    Table table = getUnpartitionedTable();
    List<Record> expectedRecords = RandomGenericData.generate(TestFixtures.SCHEMA, 2, 0L);
    addRecordsToUnpartitionedTable(table, expectedRecords);
    TestHelpers.assertRecords(run(), expectedRecords, TestFixtures.SCHEMA);
  }

  @TestTemplate
  public void testPartitionedTable() throws Exception {
    String dateStr = "2020-03-20";
    Table table = getPartitionedTable();
    List<Record> expectedRecords = RandomGenericData.generate(TestFixtures.SCHEMA, 2, 0L);
    for (Record expectedRecord : expectedRecords) {
      expectedRecord.setField("dt", dateStr);
    }
    addRecordsToPartitionedTable(table, dateStr, expectedRecords);
    TestHelpers.assertRecords(run(), expectedRecords, TestFixtures.SCHEMA);
  }

  @TestTemplate
  public void testProjection() throws Exception {
    Table table = getPartitionedTable();
    List<Record> expectedRecords = RandomGenericData.generate(TestFixtures.SCHEMA, 2, 0L);
    addRecordsToPartitionedTable(table, "2020-03-20", expectedRecords);
    // select the "data" field (fieldId == 1)
    Schema projectedSchema = TypeUtil.select(TestFixtures.SCHEMA, Sets.newHashSet(1));
    List<Row> expectedRows =
        Arrays.asList(Row.of(expectedRecords.get(0).get(0)), Row.of(expectedRecords.get(1).get(0)));
    TestHelpers.assertRows(
        run(projectedSchema, Collections.emptyList(), Collections.emptyMap()), expectedRows);
  }

  @Override
  protected RowDataConverter<GenericRecord> getConverter(Schema icebergSchema, Table table) {
    return AvroGenericRecordConverter.fromIcebergSchema(icebergSchema, table.name());
  }

  @Override
  protected ReaderFunction<GenericRecord> getReaderFunction(Schema icebergSchema, Table table, List<Expression> filters) throws Exception {
    return new AvroGenericRecordReaderFunction(
        TestFixtures.TABLE_IDENTIFIER.name(),
        new Configuration(),
        table.schema(),
        icebergSchema,
        null,
        false,
        table.io(),
        table.encryption(),
        filters);
  }

  @Override
  protected TypeInformation<GenericRecord> getTypeInfo(Schema icebergSchema) {
    org.apache.avro.Schema avroSchema =
        AvroSchemaUtil.convert(icebergSchema, TestFixtures.TABLE_IDENTIFIER.name());
    return new GenericRecordAvroTypeInfo(avroSchema);
  }

  @Override
  protected DataStream<Row> mapToRow(DataStream<GenericRecord> inputStream, Schema icebergSchema) {
    RowType rowType = FlinkSchemaUtil.convert(icebergSchema);
    org.apache.avro.Schema avroSchema =
        AvroSchemaUtil.convert(icebergSchema, TestFixtures.TABLE_IDENTIFIER.name());
    return inputStream
        .map(AvroGenericRecordToRowDataMapper.forAvroSchema(avroSchema))
        .map(new RowDataToRowMapper(rowType));
  }

}
