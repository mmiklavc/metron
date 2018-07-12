/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.metron.pcap.finalizer;

import com.google.common.collect.Iterables;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.metron.common.hadoop.SequenceFileIterable;
import org.apache.metron.job.Finalizer;
import org.apache.metron.job.JobException;
import org.apache.metron.job.Pageable;
import org.apache.metron.pcap.ConfigOptions;
import org.apache.metron.pcap.PcapFiles;
import org.apache.metron.pcap.writer.PcapResultsWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class PcapFinalizer implements Finalizer<Path> {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private PcapResultsWriter resultsWriter;

  protected PcapFinalizer() {
    this.resultsWriter = new PcapResultsWriter();
  }

  protected PcapResultsWriter getResultsWriter() {
    return resultsWriter;
  }

  @Override
  public Pageable<Path> finalizeJob(Map<String, Object> config) throws JobException {
    Configuration hadoopConfig = ConfigOptions.HADOOP_CONF.get(config, Configuration.class);
    int recPerFile = ConfigOptions.NUM_RECORDS_PER_FILE.get(config, Integer.class);
    Path interimResultPath = ConfigOptions.INTERRIM_RESULT_PATH.get(config, ConfigOptions.STRING_TO_PATH, Path.class);
    Path finalOutputPath = ConfigOptions.FINAL_OUTPUT_PATH.get(config, ConfigOptions.STRING_TO_PATH, Path.class);
    FileSystem fs = ConfigOptions.FILESYSTEM.get(config, FileSystem.class);

    SequenceFileIterable interimResults = null;
    try {
      interimResults = readInterimResults(interimResultPath, hadoopConfig, fs);
    } catch (IOException e) {
      throw new JobException("Unable to read interim job results while finalizing", e);
    }
    List<Path> outFiles = new ArrayList<>();
    try {
      Iterable<List<byte[]>> partitions = Iterables.partition(interimResults, recPerFile);
      int part = 1;
      if (partitions.iterator().hasNext()) {
        for (List<byte[]> data : partitions) {
          String outFileName = getOutputFileName(config, part++);
          if (data.size() > 0) {
            getResultsWriter().write(hadoopConfig, data, outFileName);
            outFiles.add(new Path(outFileName));
          }
        }
      } else {
        LOG.info("No results returned.");
      }
    } catch (IOException e) {
      throw new JobException("Failed to finalize results", e);
    } finally {
      try {
        interimResults.cleanup();
      } catch (IOException e) {
        LOG.warn("Unable to cleanup files in HDFS", e);
      }
    }
    return new PcapFiles(outFiles);
  }

  protected abstract String getOutputFileName(Map<String, Object> config, int partition);

  /**
   * Returns a lazily-read Iterable over a set of sequence files.
   */
  protected SequenceFileIterable readInterimResults(Path interimResultPath, Configuration config,
      FileSystem fs) throws IOException {
    List<Path> files = new ArrayList<>();
    for (RemoteIterator<LocatedFileStatus> it = fs.listFiles(interimResultPath, false);
        it.hasNext(); ) {
      Path p = it.next().getPath();
      if (p.getName().equals("_SUCCESS")) {
        fs.delete(p, false);
        continue;
      }
      files.add(p);
    }
    if (files.size() == 0) {
      LOG.info("No files to process with specified date range.");
    } else {
      LOG.debug("Interim results path={}", interimResultPath);
      Collections.sort(files, (o1, o2) -> o1.getName().compareTo(o2.getName()));
    }
    return new SequenceFileIterable(files, config);
  }
}
