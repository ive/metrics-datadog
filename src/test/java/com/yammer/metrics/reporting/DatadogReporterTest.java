package com.yammer.metrics.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yammer.metrics.core.Clock;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricPredicate;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.core.VirtualMachineMetrics;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DatadogReporterTest {

    MetricsRegistry metricsRegistry;
    MockTransport transport;
    VirtualMachineMetrics vm;
    Clock clock;
    DatadogReporter ddNoHost;
    DatadogReporter dd;

    @Before
    public void setUp() {
        metricsRegistry = new MetricsRegistry();
        transport = new MockTransport();
        clock = Clock.defaultClock();
        vm = VirtualMachineMetrics.getInstance();
        ddNoHost = new DatadogReporter(metricsRegistry, MetricPredicate.ALL,
                                       VirtualMachineMetrics.getInstance(), transport, Clock.defaultClock(),
                                       null);

        dd = new DatadogReporter(metricsRegistry, MetricPredicate.ALL,
                                 VirtualMachineMetrics.getInstance(), transport, Clock.defaultClock(),
                                 "hostname");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testBasicSend() throws IOException {
        dd.printVmMetrics = false;

        Counter counter = metricsRegistry.newCounter(DatadogReporterTest.class,
                                                     "my.counter");
        counter.inc();

        metricsRegistry.newGauge(DatadogReporterTest.class, "my.invocations",
                                 new Gauge<Long>() {
                                     private long numInovcations = 123;

                                     @Override
                                     public Long value() {
                                         return numInovcations++;
                                     }

                                 });

        assertEquals(0, transport.numRequests);
        dd.run();
        assertEquals(1, transport.numRequests);

        String body = new String(transport.lastRequest.getPostBody(), "UTF-8");
        Map<String, Object> request = new ObjectMapper().readValue(body,
                                                                   HashMap.class);

        assertEquals(1, request.keySet().size());
        List<Object> series = (List<Object>) request.get("series");

        assertEquals(2, series.size());
        Map<String, Object> counterEntry = (Map<String, Object>) series.get(0);
        Map<String, Object> gaugeEntry = (Map<String, Object>) series.get(1);

        assertEquals("com.yammer.metrics.reporting.DatadogReporterTest.my.counter",
                     counterEntry.get("metric"));
        assertEquals("counter", counterEntry.get("type"));
        List<List<Number>> points = (List<List<Number>>) counterEntry.get("points");
        assertEquals(1, points.get(0).get(1));

        assertEquals(
                "com.yammer.metrics.reporting.DatadogReporterTest.my.invocations",
                gaugeEntry.get("metric"));
        assertEquals("gauge", gaugeEntry.get("type"));
        points = (List<List<Number>>) gaugeEntry.get("points");
        assertEquals(123, points.get(0).get(1));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testIgnoresNonNumericGauge() throws IOException {
        dd.printVmMetrics = false;

        metricsRegistry.newGauge(DatadogReporterTest.class, "my.string",
                new Gauge<String>() {
                    @Override
                    public String value() {
                        return "hello";
                    }
                });
        assertEquals(0, transport.numRequests);
        dd.run();
        assertEquals(1, transport.numRequests);

        String body = new String(transport.lastRequest.getPostBody(), "UTF-8");
        Map<String, Object> request = new ObjectMapper().readValue(body,
                                                                   HashMap.class);
        assertEquals(1, request.keySet().size());

        List<Object> series = (List<Object>) request.get("series");
        assertTrue(series.isEmpty());
    }

    @Test
    public void testSupplyHostname() throws UnsupportedEncodingException {
        Counter counter = metricsRegistry.newCounter(DatadogReporterTest.class,
                                                     "my.counter");
        counter.inc();

        assertEquals(0, transport.numRequests);
        ddNoHost.run();
        assertEquals(1, transport.numRequests);
        String noHostBody = new String(transport.lastRequest.getPostBody(), "UTF-8");

        dd.run();
        assertEquals(2, transport.numRequests);
        String hostBody = new String(transport.lastRequest.getPostBody(), "UTF-8");

        assertFalse(noHostBody.contains("\"host\":\"hostname\""));
        assertTrue(hostBody.contains("\"host\":\"hostname\""));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testTaggedMeter() throws Throwable {
        Meter s = metricsRegistry.newMeter(String.class,
                                           "meter[with,tags]", "ticks", TimeUnit.SECONDS);
        s.mark();

        ddNoHost.printVmMetrics = false;
        ddNoHost.run();
        String body = new String(transport.lastRequest.getPostBody(), "UTF-8");

        Map<String, Object> request = new ObjectMapper().readValue(body,
                                                                   HashMap.class);
        List<Object> series = (List<Object>) request.get("series");

        for(Object o : series) {
            HashMap<String, Object> rec = (HashMap<String, Object>) o;
            List<String> tags = (List<String>) rec.get("tags");
            String name = rec.get("metric").toString();

            assertTrue(name.startsWith("java.lang.String.meter"));
            assertEquals("with", tags.get(0));
            assertEquals("tags", tags.get(1));
        }
    }

    @Test
    public void testHostnameTag() throws UnsupportedEncodingException {
        Counter counter = metricsRegistry.newCounter(DatadogReporterTest.class,
                "my.counter2[host:hosttag]");
        counter.inc();

        assertEquals(0, transport.numRequests);
        dd.run();
        assertEquals(1, transport.numRequests);
        String hostBody = new String(transport.lastRequest.getPostBody(), "UTF-8");

        assertTrue(hostBody.contains("\"host\":\"hosttag\""));
    }
}

