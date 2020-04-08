/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may
 * not use this file except in compliance with the License. A copy of the
 * License is located at
 *
 *    http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazonaws.samples.kaja.taxi.consumer;

import com.amazonaws.regions.Regions;
import com.amazonaws.samples.kaja.taxi.consumer.events.EventDeserializationSchema;
import com.amazonaws.samples.kaja.taxi.consumer.events.TimestampAssigner;
import com.amazonaws.samples.kaja.taxi.consumer.events.es.AverageTripDuration;
import com.amazonaws.samples.kaja.taxi.consumer.events.es.PickupCount;
import com.amazonaws.samples.kaja.taxi.consumer.events.kinesis.Event;
import com.amazonaws.samples.kaja.taxi.consumer.events.kinesis.TripEvent;
import com.amazonaws.samples.kaja.taxi.consumer.operators.CountByGeoHash;
import com.amazonaws.samples.kaja.taxi.consumer.operators.TripDurationToAverageTripDuration;
import com.amazonaws.samples.kaja.taxi.consumer.operators.TripToGeoHash;
import com.amazonaws.samples.kaja.taxi.consumer.operators.TripToTripDuration;
import com.amazonaws.samples.kaja.taxi.consumer.utils.GeoUtils;
import com.amazonaws.samples.kaja.taxi.consumer.utils.ParameterToolUtils;
import com.amazonaws.services.kinesisanalytics.flink.connectors.producer.FlinkKinesisFirehoseProducer;
import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.kinesis.FlinkKinesisConsumer;
import org.apache.flink.streaming.connectors.kinesis.config.AWSConfigConstants;
import org.apache.flink.streaming.connectors.kinesis.config.ConsumerConfigConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;


public class ProcessTaxiStream {
  private static final Logger LOG = LoggerFactory.getLogger(ProcessTaxiStream.class);

  private static final String DEFAULT_STREAM_NAME = "streaming-analytics-workshop";
  private static final String DEFAULT_REGION_NAME = Regions.getCurrentRegion()==null ? "eu-east-1" : Regions.getCurrentRegion().getName();

  private static FlinkKinesisFirehoseProducer<String> createFirehoseSinkFromStaticConfig(final String outputDeliveryStreamName) {
    Properties outputProperties = new Properties();
    outputProperties.setProperty(ConsumerConfigConstants.AWS_REGION, "us-east-1");
    FlinkKinesisFirehoseProducer<String> sink =
            new FlinkKinesisFirehoseProducer<>(outputDeliveryStreamName, new SimpleStringSchema(), outputProperties);
    return sink;
  }

  public static void main(String[] args) throws Exception {
    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

    ParameterTool parameter;

    if (env instanceof LocalStreamEnvironment) {
      //read the parameters specified from the command line
      parameter = ParameterTool.fromArgs(args);
    } else {
      //read the parameters from the Kinesis Analytics environment
      Map<String, Properties> applicationProperties = KinesisAnalyticsRuntime.getApplicationProperties();

      Properties flinkProperties = applicationProperties.get("FlinkApplicationProperties");

      if (flinkProperties == null) {
        throw new RuntimeException("Unable to load FlinkApplicationProperties properties from the Kinesis Analytics Runtime.");
      }

      parameter = ParameterToolUtils.fromApplicationProperties(flinkProperties);
    }


    //enable event time processing
    if (parameter.get("EventTime", "true").equals("true")) {
      env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
    }


    //set Kinesis consumer properties
    Properties kinesisConsumerConfig = new Properties();
    //set the region the Kinesis stream is located in
    kinesisConsumerConfig.setProperty(AWSConfigConstants.AWS_REGION, parameter.get("Region", DEFAULT_REGION_NAME));
    //obtain credentials through the DefaultCredentialsProviderChain, which includes the instance metadata
    kinesisConsumerConfig.setProperty(AWSConfigConstants.AWS_CREDENTIALS_PROVIDER, "AUTO");
    //poll new events from the Kinesis stream once every second
    kinesisConsumerConfig.setProperty(ConsumerConfigConstants.SHARD_GETRECORDS_INTERVAL_MILLIS, "1000");


    //create Kinesis source
    DataStream<Event> kinesisStream = env.addSource(new FlinkKinesisConsumer<>(
        //read events from the Kinesis stream passed in as a parameter
        parameter.get("InputStreamName", DEFAULT_STREAM_NAME),
        //deserialize events with EventSchema
        new EventDeserializationSchema(),
        //using the previously defined properties
        kinesisConsumerConfig
    ));



    DataStream<TripEvent> trips = kinesisStream
        //extract watermarks from watermark events
        .assignTimestampsAndWatermarks(new TimestampAssigner())
        //remove all events that aren't TripEvents
        .filter(event -> TripEvent.class.isAssignableFrom(event.getClass()))
        //cast Event to TripEvent
        .map(event -> (TripEvent) event)
        //remove all events with geo coordinates outside of NYC
        .filter(GeoUtils::hasValidCoordinates);


    DataStream<PickupCount> pickupCounts = trips
        //compute geo hash for every event
        .map(new TripToGeoHash())
        .keyBy("geoHash")
        //collect all events in a one hour window
        .timeWindow(Time.hours(1))
        //count events per geo hash in the one hour window
        .apply(new CountByGeoHash());


    DataStream<AverageTripDuration> tripDurations = trips
        .flatMap(new TripToTripDuration())
        .keyBy("pickupGeoHash", "airportCode")
        .timeWindow(Time.hours(1))
        .apply(new TripDurationToAverageTripDuration());


    if (parameter.has("OutputFirehoseStream")) {
      final String outputFirehoseStream = parameter.get("OutputFirehoseStream");
      //tripDurations.map(AverageTripDuration::toString).addSink(createFirehoseSinkFromStaticConfig(outputFirehoseStream));
      pickupCounts.map(PickupCount::toString).addSink(createFirehoseSinkFromStaticConfig(outputFirehoseStream));
    }


    LOG.info("Reading events from stream {}", parameter.get("InputStreamName", DEFAULT_STREAM_NAME));
    LOG.info("Writing events from Firehose stream {}", parameter.get("OutputFirehoseStream", DEFAULT_STREAM_NAME));

    env.execute();
  }
}