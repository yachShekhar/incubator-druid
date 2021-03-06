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

package org.apache.druid.data.input.orc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.InputRowParser;
import org.apache.druid.data.input.impl.TimeAndDimsParseSpec;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.indexer.HadoopDruidIndexerConfig;
import org.apache.druid.indexer.HadoopIOConfig;
import org.apache.druid.indexer.HadoopIngestionSpec;
import org.apache.druid.indexer.HadoopTuningConfig;
import org.apache.druid.indexer.HadoopyShardSpec;
import org.apache.druid.indexer.IndexGeneratorJob;
import org.apache.druid.indexer.JobHelper;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.aggregation.hyperloglog.HyperUniquesAggregatorFactory;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.QueryableIndexIndexableAdapter;
import org.apache.druid.segment.RowIterator;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.indexing.granularity.UniformGranularitySpec;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.HashBasedNumberedShardSpec;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.orc.CompressionKind;
import org.apache.orc.OrcFile;
import org.apache.orc.TypeDescription;
import org.apache.orc.Writer;
import org.joda.time.DateTime;
import org.joda.time.DateTimeComparator;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class OrcIndexGeneratorJobTest
{
  private static final AggregatorFactory[] aggs = {
      new LongSumAggregatorFactory("visited_num", "visited_num"),
      new HyperUniquesAggregatorFactory("unique_hosts", "host")
  };

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private ObjectMapper mapper;
  private HadoopDruidIndexerConfig config;
  private final String dataSourceName = "website";
  private final List<String> data = ImmutableList.of(
          "2014102200,a.example.com,100",
          "2014102200,b.exmaple.com,50",
          "2014102200,c.example.com,200",
          "2014102200,d.example.com,250",
          "2014102200,e.example.com,123",
          "2014102200,f.example.com,567",
          "2014102200,g.example.com,11",
          "2014102200,h.example.com,251",
          "2014102200,i.example.com,963",
          "2014102200,j.example.com,333",
          "2014102212,a.example.com,100",
          "2014102212,b.exmaple.com,50",
          "2014102212,c.example.com,200",
          "2014102212,d.example.com,250",
          "2014102212,e.example.com,123",
          "2014102212,f.example.com,567",
          "2014102212,g.example.com,11",
          "2014102212,h.example.com,251",
          "2014102212,i.example.com,963",
          "2014102212,j.example.com,333"
      );
  private final Interval interval = Intervals.of("2014-10-22T00:00:00Z/P1D");
  private File dataRoot;
  private File outputRoot;
  private Integer[][][] shardInfoForEachSegment = new Integer[][][]{
      {
          {0, 4},
          {1, 4},
          {2, 4},
          {3, 4}
      }
  };
  private final InputRowParser inputRowParser = new OrcHadoopInputRowParser(
      new TimeAndDimsParseSpec(
          new TimestampSpec("timestamp", "yyyyMMddHH", null),
          new DimensionsSpec(DimensionsSpec.getDefaultSchemas(ImmutableList.of("host")), null, null)
      ),
      "struct<timestamp:string,host:string,visited_num:int>",
      null
  );

  private File writeDataToLocalOrcFile(File outputDir, List<String> data) throws IOException
  {
    File outputFile = new File(outputDir, "test.orc");
    TypeDescription schema = TypeDescription.createStruct()
        .addField("timestamp", TypeDescription.createString())
        .addField("host", TypeDescription.createString())
        .addField("visited_num", TypeDescription.createInt());
    Configuration conf = new Configuration();
    Writer writer = OrcFile.createWriter(
        new Path(outputFile.getPath()),
        OrcFile.writerOptions(conf)
            .setSchema(schema)
            .stripeSize(100000)
            .bufferSize(10000)
            .compress(CompressionKind.ZLIB)
            .version(OrcFile.Version.CURRENT)
    );
    VectorizedRowBatch batch = schema.createRowBatch();
    batch.size = data.size();
    for (int idx = 0; idx < data.size(); idx++) {
      String line = data.get(idx);
      String[] lineSplit = line.split(",");
      ((BytesColumnVector) batch.cols[0]).setRef(
          idx,
          StringUtils.toUtf8(lineSplit[0]),
          0,
          lineSplit[0].length()
      );
      ((BytesColumnVector) batch.cols[1]).setRef(
          idx,
          StringUtils.toUtf8(lineSplit[1]),
          0,
          lineSplit[1].length()
      );
      ((LongColumnVector) batch.cols[2]).vector[idx] = Long.parseLong(lineSplit[2]);
    }
    writer.addRowBatch(batch);
    writer.close();

    return outputFile;
  }

  @Before
  public void setUp() throws Exception
  {
    mapper = HadoopDruidIndexerConfig.JSON_MAPPER;
    mapper.registerSubtypes(new NamedType(HashBasedNumberedShardSpec.class, "hashed"));

    dataRoot = temporaryFolder.newFolder("data");
    outputRoot = temporaryFolder.newFolder("output");
    File dataFile = writeDataToLocalOrcFile(dataRoot, data);

    HashMap<String, Object> inputSpec = new HashMap<String, Object>();
    inputSpec.put("paths", dataFile.getCanonicalPath());
    inputSpec.put("type", "static");
    inputSpec.put("inputFormat", "org.apache.hadoop.hive.ql.io.orc.OrcNewInputFormat");

    config = new HadoopDruidIndexerConfig(
        new HadoopIngestionSpec(
            new DataSchema(
                dataSourceName,
                mapper.convertValue(
                    inputRowParser,
                    Map.class
                ),
                aggs,
                new UniformGranularitySpec(Granularities.DAY, Granularities.NONE, ImmutableList.of(this.interval)),
                null,
                mapper
            ),
            new HadoopIOConfig(
                ImmutableMap.copyOf(inputSpec),
                null,
                outputRoot.getCanonicalPath()
            ),
            new HadoopTuningConfig(
                outputRoot.getCanonicalPath(),
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                false,
                false,
                false,
                ImmutableMap.of(MRJobConfig.NUM_REDUCES, "0"), //verifies that set num reducers is ignored
                false,
                true,
                null,
                true,
                null,
                false,
                false,
                null,
                null,
                null
            )
        )
    );
    config.setShardSpecs(
        loadShardSpecs(shardInfoForEachSegment)
    );
    config = HadoopDruidIndexerConfig.fromSpec(config.getSchema());
  }

  @Test
  public void testIndexGeneratorJob() throws IOException
  {
    verifyJob(new IndexGeneratorJob(config));
  }

  private void verifyJob(IndexGeneratorJob job) throws IOException
  {
    Assert.assertTrue(JobHelper.runJobs(ImmutableList.of(job), config));

    final Map<Interval, List<DataSegment>> intervalToSegments = new HashMap<>();
    IndexGeneratorJob
        .getPublishedSegments(config)
        .forEach(segment -> intervalToSegments.computeIfAbsent(segment.getInterval(), k -> new ArrayList<>())
                                              .add(segment));

    final Map<Interval, List<File>> intervalToIndexFiles = new HashMap<>();
    int segmentNum = 0;
    for (DateTime currTime = interval.getStart(); currTime.isBefore(interval.getEnd()); currTime = currTime.plusDays(1)) {
      Integer[][] shardInfo = shardInfoForEachSegment[segmentNum++];
      File segmentOutputFolder = new File(
          StringUtils.format(
              "%s/%s/%s_%s/%s",
              config.getSchema().getIOConfig().getSegmentOutputPath(),
              config.getSchema().getDataSchema().getDataSource(),
              currTime.toString(),
              currTime.plusDays(1).toString(),
              config.getSchema().getTuningConfig().getVersion()
          )
      );
      Assert.assertTrue(segmentOutputFolder.exists());
      Assert.assertEquals(shardInfo.length, segmentOutputFolder.list().length);

      for (int partitionNum = 0; partitionNum < shardInfo.length; ++partitionNum) {
        File individualSegmentFolder = new File(segmentOutputFolder, Integer.toString(partitionNum));
        Assert.assertTrue(individualSegmentFolder.exists());

        File indexZip = new File(individualSegmentFolder, "index.zip");
        Assert.assertTrue(indexZip.exists());

        intervalToIndexFiles.computeIfAbsent(new Interval(currTime, currTime.plusDays(1)), k -> new ArrayList<>())
                            .add(indexZip);
      }
    }

    Assert.assertEquals(intervalToSegments.size(), intervalToIndexFiles.size());

    segmentNum = 0;
    for (Entry<Interval, List<DataSegment>> entry : intervalToSegments.entrySet()) {
      final Interval interval = entry.getKey();
      final List<DataSegment> segments = entry.getValue();
      final List<File> indexFiles = intervalToIndexFiles.get(interval);
      Collections.sort(segments);
      indexFiles.sort(Comparator.comparing(File::getAbsolutePath));

      Assert.assertNotNull(indexFiles);
      Assert.assertEquals(segments.size(), indexFiles.size());
      Integer[][] shardInfo = shardInfoForEachSegment[segmentNum++];

      int rowCount = 0;
      for (int i = 0; i < segments.size(); i++) {
        final DataSegment dataSegment = segments.get(i);
        final File indexZip = indexFiles.get(i);
        Assert.assertEquals(config.getSchema().getTuningConfig().getVersion(), dataSegment.getVersion());
        Assert.assertEquals("local", dataSegment.getLoadSpec().get("type"));
        Assert.assertEquals(indexZip.getCanonicalPath(), dataSegment.getLoadSpec().get("path"));
        Assert.assertEquals(Integer.valueOf(9), dataSegment.getBinaryVersion());

        Assert.assertEquals(dataSourceName, dataSegment.getDataSource());
        Assert.assertEquals(1, dataSegment.getDimensions().size());
        String[] dimensions = dataSegment.getDimensions().toArray(new String[0]);
        Arrays.sort(dimensions);
        Assert.assertEquals("host", dimensions[0]);
        Assert.assertEquals("visited_num", dataSegment.getMetrics().get(0));
        Assert.assertEquals("unique_hosts", dataSegment.getMetrics().get(1));

        Integer[] hashShardInfo = shardInfo[i];
        HashBasedNumberedShardSpec spec = (HashBasedNumberedShardSpec) dataSegment.getShardSpec();
        Assert.assertEquals((int) hashShardInfo[0], spec.getPartitionNum());
        Assert.assertEquals((int) hashShardInfo[1], spec.getPartitions());

        File dir = Files.createTempDir();

        unzip(indexZip, dir);

        QueryableIndex index = HadoopDruidIndexerConfig.INDEX_IO.loadIndex(dir);
        QueryableIndexIndexableAdapter adapter = new QueryableIndexIndexableAdapter(index);

        try (RowIterator rowIt = adapter.getRows()) {
          while (rowIt.moveToNext()) {
            rowCount++;
            Assert.assertEquals(2, rowIt.getPointer().getNumMetrics());
          }
        }
      }
      Assert.assertEquals(rowCount, data.size());
    }
  }

  private Map<Long, List<HadoopyShardSpec>> loadShardSpecs(
      Integer[][][] shardInfoForEachShard
  )
  {
    Map<Long, List<HadoopyShardSpec>> shardSpecs = new TreeMap<>(DateTimeComparator.getInstance());
    int shardCount = 0;
    int segmentNum = 0;
    for (Interval segmentGranularity : config.getSegmentGranularIntervals().get()) {
      List<ShardSpec> specs = new ArrayList<>();
      for (Integer[] shardInfo : shardInfoForEachShard[segmentNum++]) {
        specs.add(new HashBasedNumberedShardSpec(shardInfo[0], shardInfo[1], null, HadoopDruidIndexerConfig.JSON_MAPPER));
      }
      List<HadoopyShardSpec> actualSpecs = Lists.newArrayListWithExpectedSize(specs.size());
      for (ShardSpec spec : specs) {
        actualSpecs.add(new HadoopyShardSpec(spec, shardCount++));
      }

      shardSpecs.put(segmentGranularity.getStartMillis(), actualSpecs);
    }

    return shardSpecs;
  }

  private void unzip(File zip, File outDir)
  {
    try {
      long size = 0L;
      final byte[] buffer = new byte[1 << 13];
      try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
        for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
          final String fileName = entry.getName();
          try (final OutputStream out = new BufferedOutputStream(
              new FileOutputStream(
                  outDir.getAbsolutePath()
                      + File.separator
                      + fileName
              ), 1 << 13
          )) {
            for (int len = in.read(buffer); len >= 0; len = in.read(buffer)) {
              if (len == 0) {
                continue;
              }
              size += len;
              out.write(buffer, 0, len);
            }
            out.flush();
          }
        }
      }
    }
    catch (IOException | RuntimeException exception) {
    }
  }
}
