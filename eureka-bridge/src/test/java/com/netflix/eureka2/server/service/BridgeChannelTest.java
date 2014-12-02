package com.netflix.eureka2.server.service;

import com.netflix.appinfo.AmazonInfo;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.eureka2.bridge.InstanceInfoConverter;
import com.netflix.eureka2.bridge.InstanceInfoConverterImpl;
import com.netflix.eureka2.registry.InstanceInfo;
import com.netflix.eureka2.registry.SampleInstanceInfo;
import com.netflix.eureka2.server.metric.EurekaServerMetricFactory;
import com.netflix.eureka2.server.registry.EurekaServerRegistry;
import com.netflix.eureka2.server.registry.EurekaServerRegistryImpl;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import rx.Observable;
import rx.Scheduler;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import rx.schedulers.TestScheduler;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

/**
 * @author David Liu
 */
@RunWith(MockitoJUnitRunner.class)
public class BridgeChannelTest {

    @Mock
    private DiscoveryClient mockV1Client;

    @Mock
    private Applications mockApplications;

    private EurekaServerRegistry<InstanceInfo> registry;

    private int period = 5;
    private TestScheduler testScheduler;
    private BridgeChannel bridgeChannel;

    private InstanceInfoConverter converter;

    private Application app1t0;
    private Application app1t1;

    private Application app2t0;
    private Application app2t1;

    @Rule
    public final ExternalResource registryResource = new ExternalResource() {

        @Override
        protected void before() throws Throwable {
            registry = spy(new EurekaServerRegistryImpl(EurekaServerMetricFactory.serverMetrics()));

            testScheduler = Schedulers.test();
            bridgeChannel = new TestBridgeChannel(registry, mockV1Client, period, SampleInstanceInfo.DiscoveryServer.build(), new BridgeChannelMetrics(), testScheduler);
            converter = new InstanceInfoConverterImpl();

            when(mockV1Client.getApplications()).thenReturn(mockApplications);

            // app1 updates
            app1t0 = new Application("app1");
            app1t0.addInstance(buildV1InstanceInfo("1-id", "app1", com.netflix.appinfo.InstanceInfo.InstanceStatus.STARTING));

            app1t1 = new Application("app1");
            app1t1.addInstance(buildV1InstanceInfo("1-id", "app1", com.netflix.appinfo.InstanceInfo.InstanceStatus.UP));

            // app2 removes
            app2t0 = new Application("app2");
            app2t0.addInstance(buildV1InstanceInfo("2-id", "app2", com.netflix.appinfo.InstanceInfo.InstanceStatus.UP));

            app2t1 = new Application("app2");
        }
    };

    @Test
    public void testAddThenUpdate() {
        when(mockApplications.getRegisteredApplications())
                .thenReturn(Arrays.asList(app1t0))
                .thenReturn(Arrays.asList(app1t1));

        bridgeChannel.connect();
        testScheduler.advanceTimeTo(period-1, TimeUnit.SECONDS);

        InstanceInfo app1t0Info = converter.fromV1(app1t0.getInstances().get(0));
        verify(registry, times(1)).register(app1t0Info);
        verify(registry, never()).update(any(InstanceInfo.class), any(Set.class));
        verify(registry, never()).unregister(any(InstanceInfo.class));

        testScheduler.advanceTimeTo(period * 2 - 1, TimeUnit.SECONDS);

        InstanceInfo app1t1Info = converter.fromV1(app1t1.getInstances().get(0));
        verify(registry, times(1)).register(app1t0Info);
        verify(registry, times(1)).update(app1t1Info, app1t1Info.diffOlder(app1t0Info));
        verify(registry, never()).unregister(any(InstanceInfo.class));
    }

    @Test
    public void testAddThenRemove() {
        when(mockApplications.getRegisteredApplications())
                .thenReturn(Arrays.asList(app2t0))
                .thenReturn(Arrays.asList(app2t1));

        bridgeChannel.connect();
        testScheduler.advanceTimeTo(period-1, TimeUnit.SECONDS);

        InstanceInfo app2t0Info = converter.fromV1(app2t0.getInstances().get(0));
        verify(registry, times(1)).register(app2t0Info);
        verify(registry, never()).update(any(InstanceInfo.class), any(Set.class));
        verify(registry, never()).unregister(any(InstanceInfo.class));

        testScheduler.advanceTimeTo(period * 2 - 1, TimeUnit.SECONDS);

        verify(registry, times(1)).register(app2t0Info);
        verify(registry, never()).update(any(InstanceInfo.class), any(Set.class));
        verify(registry, times(1)).unregister(app2t0Info);
    }


    private static com.netflix.appinfo.InstanceInfo buildV1InstanceInfo(String id, String appName, com.netflix.appinfo.InstanceInfo.InstanceStatus status) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("enableRoute53", "true");
        metadata.put("route53RecordType", "CNAME");
        metadata.put("route53NamePrefix", "some-prefix");

        com.netflix.appinfo.DataCenterInfo dataCenterInfo = AmazonInfo.Builder.newBuilder()
                .addMetadata(AmazonInfo.MetaDataKey.amiId, "amiId")
                .addMetadata(AmazonInfo.MetaDataKey.instanceId, id)
                .addMetadata(AmazonInfo.MetaDataKey.instanceType, "instanceType")
                .addMetadata(AmazonInfo.MetaDataKey.localIpv4, "0.0.0.0")
                .addMetadata(AmazonInfo.MetaDataKey.availabilityZone, "us-east-1a")
                .addMetadata(AmazonInfo.MetaDataKey.publicHostname, "public-hostname")
                .addMetadata(AmazonInfo.MetaDataKey.publicIpv4, "192.168.1.1")
                .build();

        return com.netflix.appinfo.InstanceInfo.Builder.newBuilder()
                .setAppName(appName)
                .setAppGroupName(appName + "#group")
                .setHostName(appName + "#hostname")
                .setStatus(status)
                .setIPAddr(appName + "#ip")
                .setPort(8080)
                .setSecurePort(8043)
                .setHomePageUrl("HomePage/relativeUrl", "HomePage/explicitUrl")
                .setStatusPageUrl("StatusPage/relativeUrl", "StatusPage/explicitUrl")
                .setHealthCheckUrls("HealthCheck/relativeUrl", "HealthCheck/explicitUrl", "HealthCheck/secureExplicitUrl")
                .setVIPAddress(appName + "#vipAddress")
                .setASGName(appName + "#asgName")
                .setSecureVIPAddress(appName + "#secureVipAddress")
                .setMetadata(metadata)
                .setDataCenterInfo(dataCenterInfo)
                .build();
    }

    private static class TestBridgeChannel extends BridgeChannel {

        private final DiscoveryClient testClient;

        public TestBridgeChannel(EurekaServerRegistry<InstanceInfo> registry,
                                 DiscoveryClient discoveryClient,
                                 int refreshRateSec,
                                 InstanceInfo self,
                                 BridgeChannelMetrics metrics,
                                 Scheduler scheduler) {
            super(registry, discoveryClient, refreshRateSec, self, metrics, scheduler);
            testClient = discoveryClient;
        }

        @Override
        protected Observable<com.netflix.appinfo.InstanceInfo> getV1Stream() {
            Applications applications =
                    new Applications(testClient.getApplications().getRegisteredApplications());

            return Observable.from(applications.getRegisteredApplications())
                    .flatMap(new Func1<Application, Observable<com.netflix.appinfo.InstanceInfo>>() {
                        @Override
                        public Observable<com.netflix.appinfo.InstanceInfo> call(Application application) {
                            return Observable.from(application.getInstances());
                        }
                    });
        }
    }
}
