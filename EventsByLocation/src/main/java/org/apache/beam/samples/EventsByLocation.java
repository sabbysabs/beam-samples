/*
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
package org.apache.beam.samples;

import java.text.SimpleDateFormat;
import java.util.Date;
import org.apache.beam.runners.flink.FlinkPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.aws.options.S3Options;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.DefaultValueFactory;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Sample;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;

public class EventsByLocation {

  private static final Logger LOG = LoggerFactory.getLogger(EventsByLocation.class);

  /** Specific pipeline options. */
  private interface Options extends FlinkPipelineOptions, S3Options {
    String GDELT_EVENTS_URL = "http://data.gdeltproject.org/events/";

    @Description("GDELT file date")
    @Default.InstanceFactory(GDELTFileFactory.class)
    String getDate();

    void setDate(String value);

    @Description("Input Path")
    String getInput();

    void setInput(String value);

    @Description("Output Path")
    String getOutput();

    void setOutput(String value);

    /**
     * Gets access key.
     *
     * @return the access key
     */
    @Description("AWS Access Key")
    @Validation.Required
    ValueProvider<String> getAccessKey();

    /**
     * Sets key.
     *
     * @param accessKey the access key
     */
    void setaccessKey(ValueProvider<String> accessKey);

    /**
     * Gets secret key.
     *
     * @return the secret key
     */
    @Description("AWS Secret Key")
    @Validation.Required
    ValueProvider<String> getSecretKey();

    /**
     * Sets secret key.
     *
     * @param secretKey the secret key
     */
    void setSecretKey(ValueProvider<String> secretKey);

    class GDELTFileFactory implements DefaultValueFactory<String> {
      public String create(PipelineOptions options) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");
        return format.format(new Date());
      }
    }
  }

  private static String getCountry(String row) {
    String[] fields = row.split("\\t+");
    if (fields.length > 22) {
      if (fields[21].length() > 2) {
        return fields[21].substring(0, 1);
      }
      return fields[21];
    }
    return "NA";
  }

  public static void main(String[] args) throws Exception {

    PipelineOptionsFactory.register(Options.class);
    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
    AWSCredentials awsCredentials =
        new BasicAWSCredentials(
            options.getAccessKey().toString(), options.getSecretKey().toString());
    options.setAwsCredentialsProvider(new AWSStaticCredentialsProvider(awsCredentials));

    if (options.getInput() == null) {
      options.setInput(Options.GDELT_EVENTS_URL + options.getDate() + ".export.CSV.zip");
    }
    if (options.getOutput() == null) {
      options.setOutput("/tmp/gdelt-" + options.getDate());
    }
    LOG.info(options.toString());

    Pipeline pipeline = Pipeline.create(options);
    PCollection<String> read =
        pipeline
            .apply("ReadFromGDELTFile", TextIO.read().from(options.getInput()))
            .apply("TakeASample", Sample.<String>any(10));
    read.apply(
        ParDo.of(
            new DoFn<String, Void>() {
              @ProcessElement
              public void processElement(ProcessContext c) {
                LOG.info(String.format("READ C.ELEMENT |%s|", c.element()));
              }
            }));

    read.apply(
            "ExtractLocation",
            ParDo.of(
                new DoFn<String, String>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    c.output(getCountry(c.element()));
                  }
                }))
        .apply(
            "FilterValidLocations",
            Filter.by(
                new SerializableFunction<String, Boolean>() {
                  public Boolean apply(String input) {
                    return (!input.equals("NA") && !input.startsWith("-") && input.length() == 2);
                  }
                }))
        .apply("CountByLocation", Count.<String>perElement())
        .apply(
            "ConvertToJson",
            MapElements.via(
                new SimpleFunction<KV<String, Long>, String>() {
                  public String apply(KV<String, Long> input) {
                    return "{\"" + input.getKey() + "\": " + input.getValue() + "}";
                  }
                }))
        .apply("WriteResults", TextIO.write().to(options.getOutput()));

    pipeline.run();
  }
}
