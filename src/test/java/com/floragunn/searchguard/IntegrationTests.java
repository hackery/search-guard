/*
 * Copyright 2015-2017 floragunn GmbH
 * 
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
 * 
 */

package com.floragunn.searchguard;

import io.netty.handler.ssl.OpenSsl;

import java.io.File;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.TreeSet;

import org.apache.commons.io.FileUtils;
import org.apache.http.Header;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.DocWriteResponse.Result;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryRequest;
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteRequest;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest.AliasActions;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.util.concurrent.ThreadContext.StoredContext;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.indices.InvalidIndexNameException;
import org.elasticsearch.indices.InvalidTypeNameException;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.PluginAwareNode;
import org.elasticsearch.transport.Netty4Plugin;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import com.floragunn.searchguard.action.configupdate.ConfigUpdateAction;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateRequest;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateResponse;
import com.floragunn.searchguard.action.whoami.WhoAmIAction;
import com.floragunn.searchguard.action.whoami.WhoAmIResponse;
import com.floragunn.searchguard.action.whoami.WhoAmIRequest;
import com.floragunn.searchguard.configuration.PrivilegesInterceptorImpl;
import com.floragunn.searchguard.http.HTTPClientCertAuthenticator;
import com.floragunn.searchguard.ssl.util.ExceptionUtils;
import com.floragunn.searchguard.ssl.util.SSLConfigConstants;
import com.floragunn.searchguard.support.ConfigConstants;
import com.floragunn.searchguard.test.DynamicSgConfig;
import com.floragunn.searchguard.test.SingleClusterTest;
import com.floragunn.searchguard.test.helper.cluster.ClusterConfiguration;
import com.floragunn.searchguard.test.helper.file.FileHelper;
import com.floragunn.searchguard.test.helper.rest.RestHelper;
import com.floragunn.searchguard.test.helper.rest.RestHelper.HttpResponse;

public class IntegrationTests extends SingleClusterTest {

    @Test
    public void testSearchScroll() throws Exception {
        
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                e.printStackTrace();
                
            }
        });
        
    final Settings settings = Settings.builder()
            .putList(ConfigConstants.SEARCHGUARD_AUTHCZ_REST_IMPERSONATION_USERS+".worf", "knuddel","nonexists")
            .build();
    setup(settings);
    final RestHelper rh = nonSslRestHelper();

        try (TransportClient tc = getInternalTransportClient()) {                    
            for(int i=0; i<3; i++)
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();                
        }
        
        
        System.out.println("########search");
        HttpResponse res;
        Assert.assertEquals(HttpStatus.SC_OK, (res=rh.executeGetRequest("vulcangov/_search?scroll=1m&pretty=true", encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
        
        System.out.println(res.getBody());
        int start = res.getBody().indexOf("_scroll_id") + 15;
        String scrollid = res.getBody().substring(start, res.getBody().indexOf("\"", start+1));
        System.out.println(scrollid);
        System.out.println("########search scroll");
        Assert.assertEquals(HttpStatus.SC_OK, (res=rh.executePostRequest("/_search/scroll?pretty=true", "{\"scroll_id\" : \""+scrollid+"\"}", encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());


        System.out.println("########search done");
        
        
    }
    
    @Test
    public void testEnsureInitViaRestDoesWork() throws Exception {
        
        final Settings settings = Settings.builder()
                .put(SSLConfigConstants.SEARCHGUARD_SSL_HTTP_CLIENTAUTH_MODE, "REQUIRE")
                .put("searchguard.ssl.http.enabled",true)
                .put("searchguard.ssl.http.keystore_filepath", FileHelper.getAbsoluteFilePathFromClassPath("node-0-keystore.jks"))
                .put("searchguard.ssl.http.truststore_filepath", FileHelper.getAbsoluteFilePathFromClassPath("truststore.jks"))
                .build();
        setup(Settings.EMPTY, null, settings, false);
        final RestHelper rh = restHelper(); //ssl resthelper

        rh.enableHTTPClientSSL = true;
        rh.trustHTTPServerCertificate = true;
        rh.sendHTTPClientCertificate = true;
        Assert.assertEquals(HttpStatus.SC_SERVICE_UNAVAILABLE, rh.executePutRequest("searchguard/config/0", "{}", encodeBasicHeader("___", "")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_SERVICE_UNAVAILABLE, rh.executePutRequest("searchguard/sg/config", "{}", encodeBasicHeader("___", "")).getStatusCode());
        
        
        rh.keystore = "kirk-keystore.jks";
        Assert.assertEquals(HttpStatus.SC_CREATED, rh.executePutRequest("searchguard/sg/config", "{}", encodeBasicHeader("___", "")).getStatusCode());
    
        Assert.assertFalse(rh.executeSimpleRequest("_nodes/stats?pretty").contains("\"tx_size_in_bytes\" : 0"));
        Assert.assertFalse(rh.executeSimpleRequest("_nodes/stats?pretty").contains("\"rx_count\" : 0"));
        Assert.assertFalse(rh.executeSimpleRequest("_nodes/stats?pretty").contains("\"rx_size_in_bytes\" : 0"));
        Assert.assertFalse(rh.executeSimpleRequest("_nodes/stats?pretty").contains("\"tx_count\" : 0"));

    }

    @Test
    public void testComposite() throws Exception {
    
        setup(Settings.EMPTY, new DynamicSgConfig().setSgConfig("sg_composite_config.yml").setSgRoles("sg_roles_composite.yml"), Settings.EMPTY, true);
        final RestHelper rh = nonSslRestHelper();
    
        try (TransportClient tc = getInternalTransportClient()) {                
            tc.index(new IndexRequest("starfleet").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();           
            tc.index(new IndexRequest("klingonempire").type("ships").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();      
            tc.index(new IndexRequest("public").type("legends").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();            
        }
        
        String msearchBody = 
                "{\"index\":\"starfleet\", \"type\":\"ships\", \"ignore_unavailable\": true}"+System.lineSeparator()+
                "{\"size\":10, \"query\":{\"bool\":{\"must\":{\"match_all\":{}}}}}"+System.lineSeparator()+
                "{\"index\":\"klingonempire\", \"type\":\"ships\", \"ignore_unavailable\": true}"+System.lineSeparator()+
                "{\"size\":10, \"query\":{\"bool\":{\"must\":{\"match_all\":{}}}}}"+System.lineSeparator()+
                "{\"index\":\"public\", \"ignore_unavailable\": true}"+System.lineSeparator()+
                "{\"size\":10, \"query\":{\"bool\":{\"must\":{\"match_all\":{}}}}}"+System.lineSeparator();
                         
            
        HttpResponse resc = rh.executePostRequest("_msearch", msearchBody, encodeBasicHeader("worf", "worf"));
        Assert.assertEquals(200, resc.getStatusCode());
        Assert.assertTrue(resc.getBody(), resc.getBody().contains("\"_index\":\"klingonempire\""));
        Assert.assertTrue(resc.getBody(), resc.getBody().contains("hits"));
        Assert.assertTrue(resc.getBody(), resc.getBody().contains("no permissions for [indices:data/read/search]"));
        
    }
    
    @Test
    public void testWhoAmI() throws Exception {
        setup(Settings.EMPTY, new DynamicSgConfig().setSgInternalUsers("sg_internal_empty.yml")
                .setSgRoles("sg_roles_deny.yml"), Settings.EMPTY, true);
        
        try (TransportClient tc = getUserTransportClient(clusterInfo, "spock-keystore.jks", Settings.EMPTY)) {  
            WhoAmIResponse wres = tc.execute(WhoAmIAction.INSTANCE, new WhoAmIRequest()).actionGet();  
            System.out.println(wres);
            Assert.assertEquals(wres.toString(), "CN=spock,OU=client,O=client,L=Test,C=DE", wres.getDn());
            Assert.assertFalse(wres.toString(), wres.isAdmin());
            Assert.assertFalse(wres.toString(), wres.isAuthenticated());
            Assert.assertFalse(wres.toString(), wres.isNodeCertificateRequest());

        }
        
        try (TransportClient tc = getUserTransportClient(clusterInfo, "node-0-keystore.jks", Settings.EMPTY)) {  
            WhoAmIResponse wres = tc.execute(WhoAmIAction.INSTANCE, new WhoAmIRequest()).actionGet();    
            System.out.println(wres);
            Assert.assertEquals(wres.toString(), "CN=node-0.example.com,OU=SSL,O=Test,L=Test,C=DE", wres.getDn());
            Assert.assertFalse(wres.toString(), wres.isAdmin());
            Assert.assertFalse(wres.toString(), wres.isAuthenticated());
            Assert.assertTrue(wres.toString(), wres.isNodeCertificateRequest());

        }
    }
    
    @Test
    public void testNotInsecure() throws Exception {
        setup(Settings.EMPTY, new DynamicSgConfig().setSgRoles("sg_roles_deny.yml"), Settings.EMPTY, true);
        final RestHelper rh = nonSslRestHelper();
        
        try (TransportClient tc = getInternalTransportClient()) {               
            //create indices and mapping upfront
            tc.index(new IndexRequest("test").type("type1").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"field2\":\"init\"}", XContentType.JSON)).actionGet();           
            tc.index(new IndexRequest("lorem").type("type1").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"field2\":\"init\"}", XContentType.JSON)).actionGet();      
        
            WhoAmIResponse wres = tc.execute(WhoAmIAction.INSTANCE, new WhoAmIRequest()).actionGet();   
            System.out.println(wres);
            Assert.assertEquals("CN=kirk,OU=client,O=client,L=Test,C=DE", wres.getDn());
            Assert.assertTrue(wres.isAdmin());
            Assert.assertTrue(wres.toString(), wres.isAuthenticated());
            Assert.assertFalse(wres.toString(), wres.isNodeCertificateRequest());
        }
        
        HttpResponse res = rh.executePutRequest("test/_mapping/type1?pretty", "{\"properties\": {\"name\":{\"type\":\"text\"}}}", encodeBasicHeader("writer", "writer"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, res.getStatusCode());  
        
        res = rh.executePostRequest("_cluster/reroute", "{}", encodeBasicHeader("writer", "writer"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, res.getStatusCode());  
        
        try (TransportClient tc = getUserTransportClient(clusterInfo, "spock-keystore.jks", Settings.EMPTY)) {               
            //create indices and mapping upfront
            try {
                tc.admin().indices().putMapping(new PutMappingRequest("test").type("typex").source("fieldx","type=text")).actionGet();
                Assert.fail();
            } catch (ElasticsearchSecurityException e) {
                Assert.assertTrue(e.toString(),e.getMessage().contains("no permissions for"));
            }          
            
            try {
                tc.admin().cluster().reroute(new ClusterRerouteRequest()).actionGet();
                Assert.fail();
            } catch (ElasticsearchSecurityException e) {
                Assert.assertTrue(e.toString(),e.getMessage().contains("no permissions for [cluster:admin/reroute]"));
            }
            
            WhoAmIResponse wres = tc.execute(WhoAmIAction.INSTANCE, new WhoAmIRequest()).actionGet();                
            Assert.assertEquals("CN=spock,OU=client,O=client,L=Test,C=DE", wres.getDn());
            Assert.assertFalse(wres.isAdmin());
            Assert.assertTrue(wres.toString(), wres.isAuthenticated());
            Assert.assertFalse(wres.toString(), wres.isNodeCertificateRequest());
        }

    }
    
    @Test
    public void testBulkShards() throws Exception {
    
        setup(Settings.EMPTY, new DynamicSgConfig().setSgRoles("sg_roles_bs.yml"), Settings.EMPTY, true);
        final RestHelper rh = nonSslRestHelper();
        
        try (TransportClient tc = getInternalTransportClient()) {               
            //create indices and mapping upfront
            tc.index(new IndexRequest("test").type("type1").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"field2\":\"init\"}", XContentType.JSON)).actionGet();           
            tc.index(new IndexRequest("lorem").type("type1").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"field2\":\"init\"}", XContentType.JSON)).actionGet();      
        }
        
        String bulkBody = 
        "{ \"index\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"1\" } }"+System.lineSeparator()+
        "{ \"field2\" : \"value1\" }" +System.lineSeparator()+
        "{ \"index\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"2\" } }"+System.lineSeparator()+
        "{ \"field2\" : \"value2\" }"+System.lineSeparator()+
        "{ \"index\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"3\" } }"+System.lineSeparator()+
        "{ \"field2\" : \"value2\" }"+System.lineSeparator()+
        "{ \"index\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"4\" } }"+System.lineSeparator()+
        "{ \"field2\" : \"value2\" }"+System.lineSeparator()+
        "{ \"index\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"5\" } }"+System.lineSeparator()+
        "{ \"field2\" : \"value2\" }"+System.lineSeparator()+
        "{ \"index\" : { \"_index\" : \"lorem\", \"_type\" : \"type1\", \"_id\" : \"1\" } }"+System.lineSeparator()+
        "{ \"field2\" : \"value2\" }"+System.lineSeparator()+
        "{ \"index\" : { \"_index\" : \"lorem\", \"_type\" : \"type1\", \"_id\" : \"2\" } }"+System.lineSeparator()+
        "{ \"field2\" : \"value2\" }"+System.lineSeparator()+
        "{ \"index\" : { \"_index\" : \"lorem\", \"_type\" : \"type1\", \"_id\" : \"3\" } }"+System.lineSeparator()+
        "{ \"field2\" : \"value2\" }"+System.lineSeparator()+
        "{ \"index\" : { \"_index\" : \"lorem\", \"_type\" : \"type1\", \"_id\" : \"4\" } }"+System.lineSeparator()+
        "{ \"field2\" : \"value2\" }"+System.lineSeparator()+
        "{ \"index\" : { \"_index\" : \"lorem\", \"_type\" : \"type1\", \"_id\" : \"5\" } }"+System.lineSeparator()+
        "{ \"field2\" : \"value2\" }"+System.lineSeparator()+
        "{ \"delete\" : { \"_index\" : \"lorem\", \"_type\" : \"type1\", \"_id\" : \"5\" } }"+System.lineSeparator();
       
        System.out.println("############ _bulk");
        HttpResponse res = rh.executePostRequest("_bulk?refresh=true&pretty=true", bulkBody, encodeBasicHeader("worf", "worf"));
        System.out.println(res.getBody());
        Assert.assertEquals(HttpStatus.SC_OK, res.getStatusCode());  
        Assert.assertTrue(res.getBody().contains("\"errors\" : true"));
        Assert.assertTrue(res.getBody().contains("\"status\" : 201"));
        Assert.assertTrue(res.getBody().contains("no permissions for"));
        
        System.out.println("############ check shards");
        System.out.println(rh.executeGetRequest("_cat/shards?v", encodeBasicHeader("nagilum", "nagilum")));

        
    }

    @Test
    public void testConfigHotReload() throws Exception {
    
        setup();
        RestHelper rh = nonSslRestHelper();
        Header spock = encodeBasicHeader("spock", "spock");
          
        for (Iterator<TransportAddress> iterator = clusterInfo.httpAdresses.iterator(); iterator.hasNext();) {
            TransportAddress TransportAddress = (TransportAddress) iterator.next();
            HttpResponse res = rh.executeRequest(new HttpGet("http://"+TransportAddress.getAddress()+":"+TransportAddress.getPort() + "/" + "_searchguard/authinfo?pretty=true"), spock);
            Assert.assertTrue(res.getBody().contains("spock"));
            Assert.assertFalse(res.getBody().contains("additionalrole"));
            Assert.assertTrue(res.getBody().contains("vulcan"));
        }
        
        try (TransportClient tc = getInternalTransportClient()) {   
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
            tc.index(new IndexRequest("searchguard").type("sg").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("internalusers").source("internalusers", FileHelper.readYamlContent("sg_internal_users_spock_add_roles.yml"))).actionGet();
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());   
        } 
        
        for (Iterator<TransportAddress> iterator = clusterInfo.httpAdresses.iterator(); iterator.hasNext();) {
            TransportAddress TransportAddress = (TransportAddress) iterator.next();
            log.debug("http://"+TransportAddress.getAddress()+":"+TransportAddress.getPort());
            HttpResponse res = rh.executeRequest(new HttpGet("http://"+TransportAddress.getAddress()+":"+TransportAddress.getPort() + "/" + "_searchguard/authinfo?pretty=true"), spock);
            Assert.assertTrue(res.getBody().contains("spock"));
            Assert.assertTrue(res.getBody().contains("additionalrole1"));
            Assert.assertTrue(res.getBody().contains("additionalrole2"));
            Assert.assertFalse(res.getBody().contains("starfleet"));
        }
        
        try (TransportClient tc = getInternalTransportClient()) {    
            Assert.assertEquals(3, tc.admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());
            tc.index(new IndexRequest("searchguard").type("sg").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("config").source("config", FileHelper.readYamlContent("sg_config_anon.yml"))).actionGet();
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());   
        }
        
        for (Iterator<TransportAddress> iterator = clusterInfo.httpAdresses.iterator(); iterator.hasNext();) {
            TransportAddress TransportAddress = (TransportAddress) iterator.next();
            HttpResponse res = rh.executeRequest(new HttpGet("http://"+TransportAddress.getAddress()+":"+TransportAddress.getPort() + "/" + "_searchguard/authinfo?pretty=true"));
            log.debug(res.getBody());
            Assert.assertTrue(res.getBody().contains("sg_role_host1"));
            Assert.assertTrue(res.getBody().contains("sg_anonymous"));
            Assert.assertTrue(res.getBody().contains("name=sg_anonymous"));
            Assert.assertTrue(res.getBody().contains("roles=[sg_anonymous_backendrole]"));
            Assert.assertEquals(200, res.getStatusCode());
        }
    }

    @Test
    public void testCreateIndex() throws Exception {
    
        setup();
        RestHelper rh = nonSslRestHelper();
              
        HttpResponse res;
        Assert.assertEquals("Unable to create index 'nag'", HttpStatus.SC_OK, rh.executePutRequest("nag1", null, encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals("Unable to create index 'starfleet_library'", HttpStatus.SC_OK, rh.executePutRequest("starfleet_library", null, encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        
        clusterHelper.waitForCluster(ClusterHealthStatus.GREEN, TimeValue.timeValueSeconds(10), clusterInfo.numNodes);
        
        Assert.assertEquals("Unable to close index 'starfleet_library'", HttpStatus.SC_OK, rh.executePostRequest("starfleet_library/_close", null, encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        
        Assert.assertEquals("Unable to open index 'starfleet_library'", HttpStatus.SC_OK, (res = rh.executePostRequest("starfleet_library/_open", null, encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
        Assert.assertTrue("open index 'starfleet_library' not acknowledged", res.getBody().contains("acknowledged"));
        Assert.assertFalse("open index 'starfleet_library' not acknowledged", res.getBody().contains("false"));
        
        clusterHelper.waitForCluster(ClusterHealthStatus.GREEN, TimeValue.timeValueSeconds(10), clusterInfo.numNodes);
        
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePutRequest("public", null, encodeBasicHeader("spock", "spock")).getStatusCode());
        
        
    }

    @Test
    public void testDefaultConfig() throws Exception {
        
        System.setProperty("sg.default_init.dir", new File("./sgconfig").getAbsolutePath());
        final Settings settings = Settings.builder()
                .put(ConfigConstants.SEARCHGUARD_ALLOW_DEFAULT_INIT_SGINDEX, true)
                .build();
        setup(Settings.EMPTY, null, settings, false);
        RestHelper rh = nonSslRestHelper();
        Thread.sleep(10000);
        
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("", encodeBasicHeader("admin", "admin")).getStatusCode());
    }

    @Test
    public void testDisabled() throws Exception {
    
        final Settings settings = Settings.builder().put("searchguard.disabled", true).build();
        
        setup(Settings.EMPTY, null, settings, false);
        RestHelper rh = nonSslRestHelper();
            
        HttpResponse resc = rh.executeGetRequest("_search");
        Assert.assertEquals(200, resc.getStatusCode());
        Assert.assertTrue(resc.getBody(), resc.getBody().contains("hits"));        
    }

    @Test
    public void testDiscoveryWithoutInitialization() throws Exception {  
        setup(Settings.EMPTY, null, Settings.EMPTY, false);
        Assert.assertEquals(3, clusterHelper.nodeClient().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
        Assert.assertEquals(ClusterHealthStatus.GREEN, clusterHelper.nodeClient().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getStatus());
    }

    @Test
    public void testDnParsingCertAuth() throws Exception {
        Settings settings = Settings.builder()
                .put("username_attribute", "cn")
                .put("roles_attribute", "l")
                .build();
        HTTPClientCertAuthenticator auth = new HTTPClientCertAuthenticator(settings, null);
        Assert.assertEquals("abc", auth.extractCredentials(null, newThreadContext("cn=abc,cn=xxx,l=ert,st=zui,c=qwe")).getUsername());
        Assert.assertEquals("abc", auth.extractCredentials(null, newThreadContext("cn=abc,l=ert,st=zui,c=qwe")).getUsername());
        Assert.assertEquals("abc", auth.extractCredentials(null, newThreadContext("CN=abc,L=ert,st=zui,c=qwe")).getUsername());     
        Assert.assertEquals("abc", auth.extractCredentials(null, newThreadContext("l=ert,cn=abc,st=zui,c=qwe")).getUsername());
        Assert.assertNull(auth.extractCredentials(null, newThreadContext("L=ert,CN=abc,c,st=zui,c=qwe")));
        Assert.assertEquals("abc", auth.extractCredentials(null, newThreadContext("l=ert,st=zui,c=qwe,cn=abc")).getUsername());
        Assert.assertEquals("abc", auth.extractCredentials(null, newThreadContext("L=ert,st=zui,c=qwe,CN=abc")).getUsername()); 
        Assert.assertEquals("L=ert,st=zui,c=qwe", auth.extractCredentials(null, newThreadContext("L=ert,st=zui,c=qwe")).getUsername()); 
        Assert.assertArrayEquals(new String[] {"ert"}, auth.extractCredentials(null, newThreadContext("cn=abc,l=ert,st=zui,c=qwe")).getBackendRoles().toArray(new String[0]));
        Assert.assertArrayEquals(new String[] {"bleh", "ert"}, new TreeSet<>(auth.extractCredentials(null, newThreadContext("cn=abc,l=ert,L=bleh,st=zui,c=qwe")).getBackendRoles()).toArray(new String[0]));
        
        settings = Settings.builder()
                .build();
        auth = new HTTPClientCertAuthenticator(settings, null);
        Assert.assertEquals("cn=abc,l=ert,st=zui,c=qwe", auth.extractCredentials(null, newThreadContext("cn=abc,l=ert,st=zui,c=qwe")).getUsername());
    }
    
    private ThreadContext newThreadContext(String sslPrincipal) {
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        threadContext.putTransient(ConfigConstants.SG_SSL_PRINCIPAL, sslPrincipal);
        return threadContext;
    }

    @Test
    public void testDNSpecials() throws Exception {
    
        final Settings settings = Settings.builder()
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_FILEPATH, FileHelper.getAbsoluteFilePathFromClassPath("node-untspec5-keystore.p12"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "1")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_TYPE, "PKCS12")
                .putList("searchguard.nodes_dn", "EMAILADDRESS=unt@tst.com,CN=node-untspec5.example.com,OU=SSL,O=Te\\, st,L=Test,C=DE")
                .putList("searchguard.authcz.admin_dn", "EMAILADDRESS=unt@xxx.com,CN=node-untspec6.example.com,OU=SSL,O=Te\\, st,L=Test,C=DE")
                .put("searchguard.cert.oid","1.2.3.4.5.6")
                .build();
        
        
        Settings tcSettings = Settings.builder()
                .put("searchguard.ssl.transport.keystore_filepath", FileHelper.getAbsoluteFilePathFromClassPath("node-untspec6-keystore.p12"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_TYPE, "PKCS12")
                .build();
        
        setup(tcSettings, new DynamicSgConfig(), settings, true);
        RestHelper rh = nonSslRestHelper();
        
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, rh.executeGetRequest("").getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("", encodeBasicHeader("worf", "worf")).getStatusCode());
    
    }
    
    @Test
    public void testDNSpecials1() throws Exception {
    
        final Settings settings = Settings.builder()
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_FILEPATH, FileHelper.getAbsoluteFilePathFromClassPath("node-untspec5-keystore.p12"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS, "1")
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_TYPE, "PKCS12")
                .putList("searchguard.nodes_dn", "EMAILADDRESS=unt@tst.com,CN=node-untspec5.example.com,OU=SSL,O=Te\\, st,L=Test,C=DE")
                .putList("searchguard.authcz.admin_dn", "EMAILADDREss=unt@xxx.com,  cn=node-untspec6.example.com, OU=SSL,O=Te\\, st,L=Test, c=DE")
                .put("searchguard.cert.oid","1.2.3.4.5.6")
                .build();
        
        
        Settings tcSettings = Settings.builder()
                .put("searchguard.ssl.transport.keystore_filepath", FileHelper.getAbsoluteFilePathFromClassPath("node-untspec6-keystore.p12"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_TYPE, "PKCS12")
                .build();
        
        setup(tcSettings, new DynamicSgConfig(), settings, true);
        RestHelper rh = nonSslRestHelper();
        
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, rh.executeGetRequest("").getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("", encodeBasicHeader("worf", "worf")).getStatusCode());
    }

    @Test
    public void testEnsureOpenSSLAvailability() {
        Assume.assumeTrue(allowOpenSSL);
        Assert.assertTrue(String.valueOf(OpenSsl.unavailabilityCause()), OpenSsl.isAvailable());
    }

    @Test
    public void testFilteredAlias() throws Exception {
    
        setup();
        
        try (TransportClient tc = getInternalTransportClient()) {

            tc.index(new IndexRequest("theindex").type("type1").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("otherindex").type("type1").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAliasAction(AliasActions.add().alias("alias1").filter(QueryBuilders.termQuery("_type", "type1")).index("theindex"))).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAliasAction(AliasActions.add().alias("alias2").filter(QueryBuilders.termQuery("_type", "type2")).index("theindex"))).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAliasAction(AliasActions.add().alias("alias3").filter(QueryBuilders.termQuery("_type", "type2")).index("otherindex"))).actionGet();
        }
        
        
        RestHelper rh = nonSslRestHelper();
    
        //sg_user1 -> worf
        //sg_user2 -> picard
        
        HttpResponse resc = rh.executeGetRequest("alias*/_search", encodeBasicHeader("worf", "worf"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, resc.getStatusCode());
        
        resc =  rh.executeGetRequest("theindex/_search", encodeBasicHeader("nagilum", "nagilum"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, resc.getStatusCode());
        
        resc =  rh.executeGetRequest("alias3/_search", encodeBasicHeader("nagilum", "nagilum"));
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());

        resc =  rh.executeGetRequest("_cat/indices", encodeBasicHeader("nagilum", "nagilum"));
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
        
    }
    
    @Test
    public void testIndexTypeEvaluation() throws Exception {
    
        setup();
    
        try (TransportClient tc = getInternalTransportClient()) {          
            tc.index(new IndexRequest("foo1").type("bar").id("1").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("foo2").type("bar").id("2").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("foo").type("baz").id("3").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":3}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("fooba").type("z").id("4").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":4}", XContentType.JSON)).actionGet();
            
            try {
                tc.index(new IndexRequest("x#a").type("xxx").id("4a").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":4}", XContentType.JSON)).actionGet();
                Assert.fail("Indexname can contain #");
            } catch (InvalidIndexNameException e) {
                //expected
            }
            
            
            try {
                tc.index(new IndexRequest("xa").type("x#a").id("4a").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":4}", XContentType.JSON)).actionGet();
                Assert.fail("Typename can contain #");
            } catch (InvalidTypeNameException e) {
                //expected
            }
        }
        
        RestHelper rh = nonSslRestHelper();
    
        HttpResponse  resc = rh.executeGetRequest("/foo1/bar/_search?pretty", encodeBasicHeader("baz", "worf"));
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
        Assert.assertTrue(resc.getBody().contains("\"content\" : 1"));
        
        resc = rh.executeGetRequest("/foo2/bar/_search?pretty", encodeBasicHeader("baz", "worf"));
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
        Assert.assertTrue(resc.getBody().contains("\"content\" : 2"));
        
        resc = rh.executeGetRequest("/foo/baz/_search?pretty", encodeBasicHeader("baz", "worf"));
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
        Assert.assertTrue(resc.getBody().contains("\"content\" : 3"));
        
        resc = rh.executeGetRequest("/fooba/z/_search?pretty", encodeBasicHeader("baz", "worf"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, resc.getStatusCode());        
    
        resc = rh.executeGetRequest("/foo1/bar/1?pretty", encodeBasicHeader("baz", "worf"));
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
        Assert.assertTrue(resc.getBody().contains("\"found\" : true"));
        Assert.assertTrue(resc.getBody().contains("\"content\" : 1"));
        
        resc = rh.executeGetRequest("/foo2/bar/2?pretty", encodeBasicHeader("baz", "worf"));
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
        Assert.assertTrue(resc.getBody().contains("\"content\" : 2"));
        Assert.assertTrue(resc.getBody().contains("\"found\" : true"));
        
        resc = rh.executeGetRequest("/foo/baz/3?pretty", encodeBasicHeader("baz", "worf"));
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
        Assert.assertTrue(resc.getBody().contains("\"content\" : 3"));
        Assert.assertTrue(resc.getBody().contains("\"found\" : true"));
    
        resc = rh.executeGetRequest("/fooba/z/4?pretty", encodeBasicHeader("baz", "worf"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, resc.getStatusCode());
    
        resc = rh.executeGetRequest("/foo*/_search?pretty", encodeBasicHeader("baz", "worf"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, resc.getStatusCode());
    
        resc = rh.executeGetRequest("/foo*,-fooba/bar/_search?pretty", encodeBasicHeader("baz", "worf"));
        Assert.assertEquals(200, resc.getStatusCode());
        Assert.assertTrue(resc.getBody().contains("\"content\" : 1"));
        Assert.assertTrue(resc.getBody().contains("\"content\" : 2"));
    }

    @Test
    public void testIndices() throws Exception {
    
        setup();
    
        try (TransportClient tc = getInternalTransportClient()) {
            tc.index(new IndexRequest("nopermindex").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
    
            tc.index(new IndexRequest("logstash-1").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("logstash-2").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("logstash-3").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("logstash-4").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
    
            String date = new SimpleDateFormat("YYYY.MM.dd").format(new Date());
            tc.index(new IndexRequest("logstash-"+date).type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
        }
        
        RestHelper rh = nonSslRestHelper();
        
        HttpResponse res = null;
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executeGetRequest("/logstash-1/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
    
        //nonexistent index with permissions
        Assert.assertEquals(HttpStatus.SC_NOT_FOUND, (res = rh.executeGetRequest("/logstash-nonex/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
    
        //existent index without permissions
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/nopermindex/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());

        //nonexistent index without permissions
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/does-not-exist-and-no-perm/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
    
        //existent index with permissions
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executeGetRequest("/logstash-1/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());

        //nonexistent index with failed login
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, (res = rh.executeGetRequest("/logstash-nonex/_search", encodeBasicHeader("nouser", "nosuer"))).getStatusCode());   
        
        //nonexistent index with no login
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, (res = rh.executeGetRequest("/logstash-nonex/_search")).getStatusCode());   
        
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
        
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/_all/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
    
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/*/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());        
    
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/nopermindex,logstash-1,nonexist/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
        
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/logstash-1,nonexist/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
        
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/nonexist/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
        
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executeGetRequest("/%3Clogstash-%7Bnow%2Fd%7D%3E/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
    
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/%3Cnonex-%7Bnow%2Fd%7D%3E/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
        
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executeGetRequest("/%3Clogstash-%7Bnow%2Fd%7D%3E,logstash-*/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
        
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executeGetRequest("/%3Clogstash-%7Bnow%2Fd%7D%3E,logstash-1/_search", encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
        
        Assert.assertEquals(HttpStatus.SC_CREATED, (res = rh.executePutRequest("/logstash-b/logs/1", "{}",encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
    
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executePutRequest("/%3Clogstash-cnew-%7Bnow%2Fd%7D%3E", "{}",encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
        
        Assert.assertEquals(HttpStatus.SC_CREATED, (res = rh.executePutRequest("/%3Clogstash-new-%7Bnow%2Fd%7D%3E/logs/1", "{}",encodeBasicHeader("logstash", "nagilum"))).getStatusCode());
    
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executeGetRequest("/_cat/indices?v" ,encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
    
        System.out.println(res.getBody());
        Assert.assertTrue(res.getBody().contains("logstash-b"));
        Assert.assertTrue(res.getBody().contains("logstash-new-20"));
        Assert.assertTrue(res.getBody().contains("logstash-cnew-20"));
        Assert.assertFalse(res.getBody().contains("<"));
    }
    
    @Test
    public void testAliases() throws Exception {

        final Settings settings = Settings.builder()
                .put(ConfigConstants.SEARCHGUARD_ROLES_MAPPING_RESOLUTION, "BOTH")
                .build();

        setup(settings);
    
        try (TransportClient tc = getInternalTransportClient()) {
            tc.index(new IndexRequest("nopermindex").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
    
            tc.index(new IndexRequest("logstash-1").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("logstash-2").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("logstash-3").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("logstash-4").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("logstash-5").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("logstash-del").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("logstash-del-ok").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();

            String date = new SimpleDateFormat("YYYY.MM.dd").format(new Date());
            tc.index(new IndexRequest("logstash-"+date).type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
        
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAliasAction(AliasActions.add().indices("nopermindex").alias("nopermalias"))).actionGet();
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAliasAction(AliasActions.add().indices("searchguard").alias("mysgi"))).actionGet();
        }
        
        RestHelper rh = nonSslRestHelper();
        
        HttpResponse res = null;
        
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executePostRequest("/mysgi/sg", "{}",encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executeGetRequest("/mysgi/_search?pretty", encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
        assertContains(res, "*\"hits\" : {*\"total\" : 0,*\"hits\" : [ ]*");
        
        System.out.println("#### add alias to allowed index");
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executePutRequest("/logstash-1/_alias/alog1", "",encodeBasicHeader("aliasmngt", "nagilum"))).getStatusCode());

        System.out.println("#### add alias to not existing (no perm)");
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executePutRequest("/nonexitent/_alias/alnp", "",encodeBasicHeader("aliasmngt", "nagilum"))).getStatusCode());
        
        System.out.println("#### add alias to not existing (with perm)");
        Assert.assertEquals(HttpStatus.SC_NOT_FOUND, (res = rh.executePutRequest("/logstash-nonex/_alias/alnp", "",encodeBasicHeader("aliasmngt", "nagilum"))).getStatusCode());
        
        System.out.println("#### add alias to not allowed index");
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executePutRequest("/nopermindex/_alias/alnp", "",encodeBasicHeader("aliasmngt", "nagilum"))).getStatusCode());

        String aliasRemoveIndex = "{"+
            "\"actions\" : ["+
               "{ \"add\":  { \"index\": \"logstash-del-ok\", \"alias\": \"logstash-del\" } },"+
               "{ \"remove_index\": { \"index\": \"logstash-del\" } }  "+
            "]"+
        "}";
        
        System.out.println("#### remove_index");
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executePostRequest("/_aliases", aliasRemoveIndex,encodeBasicHeader("aliasmngt", "nagilum"))).getStatusCode());

        
        System.out.println("#### get alias for permitted index");
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executeGetRequest("/logstash-1/_alias/alog1", encodeBasicHeader("aliasmngt", "nagilum"))).getStatusCode());

        
        System.out.println("#### get alias for all indices");
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/_alias/alog1", encodeBasicHeader("aliasmngt", "nagilum"))).getStatusCode());

        
        System.out.println("#### get alias no perm");
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executeGetRequest("/_alias/nopermalias", encodeBasicHeader("aliasmngt", "nagilum"))).getStatusCode());
        
        String alias =
        "{"+
          "\"aliases\": {"+
            "\"alias1\": {}"+
          "}"+
        "}";
        
        
        System.out.println("#### create alias along with index");
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, (res = rh.executePutRequest("/beats-withalias", alias,encodeBasicHeader("aliasmngt", "nagilum"))).getStatusCode());        
    }

    @Test
    public void testMultiget() throws Exception {
    
        setup();
    
        try (TransportClient tc = getInternalTransportClient()) {
            tc.index(new IndexRequest("mindex1").type("type").id("1").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("mindex2").type("type").id("2").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}", XContentType.JSON)).actionGet();
        }
    
        //sg_multiget -> picard
        
        
            String mgetBody = "{"+
            "\"docs\" : ["+
                "{"+
                     "\"_index\" : \"mindex1\","+
                    "\"_type\" : \"type\","+
                    "\"_id\" : \"1\""+
               " },"+
               " {"+
                   "\"_index\" : \"mindex2\","+
                   " \"_type\" : \"type\","+
                   " \"_id\" : \"2\""+
                "}"+
            "]"+
        "}";
       
       RestHelper rh = nonSslRestHelper();
       HttpResponse resc = rh.executePostRequest("_mget?refresh=true", mgetBody, encodeBasicHeader("picard", "picard"));
       System.out.println(resc.getBody());
       Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
       Assert.assertFalse(resc.getBody().contains("type2"));
        
    }

    @Test
    public void testRestImpersonation() throws Exception {
    
        final Settings settings = Settings.builder()
                 .putList(ConfigConstants.SEARCHGUARD_AUTHCZ_REST_IMPERSONATION_USERS+".spock", "knuddel","userwhonotexists").build();
 
        setup(settings);
        
        RestHelper rh = nonSslRestHelper();
        
        //knuddel:
        //    hash: _rest_impersonation_only_
    
        HttpResponse resp;
        resp = rh.executeGetRequest("/_searchguard/authinfo", new BasicHeader("sg_impersonate_as", "knuddel"), encodeBasicHeader("worf", "worf"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, resp.getStatusCode());
    
        resp = rh.executeGetRequest("/_searchguard/authinfo", new BasicHeader("sg_impersonate_as", "knuddel"), encodeBasicHeader("spock", "spock"));
        Assert.assertEquals(HttpStatus.SC_OK, resp.getStatusCode());
        Assert.assertTrue(resp.getBody().contains("name=knuddel"));
        Assert.assertFalse(resp.getBody().contains("spock"));
        
        resp = rh.executeGetRequest("/_searchguard/authinfo", new BasicHeader("sg_impersonate_as", "userwhonotexists"), encodeBasicHeader("spock", "spock"));
        System.out.println(resp.getBody());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, resp.getStatusCode());
    
        resp = rh.executeGetRequest("/_searchguard/authinfo", new BasicHeader("sg_impersonate_as", "invalid"), encodeBasicHeader("spock", "spock"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, resp.getStatusCode());
    }

    @Test
    public void testSingle() throws Exception {
    
        setup();
    
        try (TransportClient tc = getInternalTransportClient()) {          
            tc.index(new IndexRequest("shakespeare").type("type").id("1").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
                      
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        }
    
        RestHelper rh = nonSslRestHelper();
        //sg_shakespeare -> picard
    
        HttpResponse resc = rh.executeGetRequest("shakespeare/_search", encodeBasicHeader("picard", "picard"));
        System.out.println(resc.getBody());
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
        Assert.assertTrue(resc.getBody().contains("\"content\":1"));
        
        resc = rh.executeHeadRequest("shakespeare", encodeBasicHeader("picard", "picard"));
        Assert.assertEquals(HttpStatus.SC_OK, resc.getStatusCode());
        
    }

    @Test
    public void testSnapshot() throws Exception {
    
        final Settings settings = Settings.builder()
                .putList("path.repo", repositoryPath.getRoot().getAbsolutePath())
                .put("searchguard.enable_snapshot_restore_privilege", true)
                .put("searchguard.check_snapshot_restore_write_privileges", false)
                .build();
    
        setup(settings);
    
        try (TransportClient tc = getInternalTransportClient()) {    
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
                
            tc.admin().cluster().putRepository(new PutRepositoryRequest("vulcangov").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/vulcangov"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("vulcangov", "vulcangov_1").indices("vulcangov").includeGlobalState(true).waitForCompletion(true)).actionGet();
    
            tc.admin().cluster().putRepository(new PutRepositoryRequest("searchguard").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/searchguard"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("searchguard", "searchguard_1").indices("searchguard").includeGlobalState(false).waitForCompletion(true)).actionGet();
    
            tc.admin().cluster().putRepository(new PutRepositoryRequest("all").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/all"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("all", "all_1").indices("*").includeGlobalState(false).waitForCompletion(true)).actionGet();
        }
    
        RestHelper rh = nonSslRestHelper();
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/vulcangov", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/vulcangov/vulcangov_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_$1\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"include_global_state\": true, \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_with_global_state_$1\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","", encodeBasicHeader("worf", "worf")).getStatusCode());
        // Try to restore vulcangov index as searchguard index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"indices\": \"vulcangov\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore searchguard index.
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/searchguard", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/searchguard/searchguard_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/searchguard/searchguard_1/_restore?wait_for_completion=true","", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/searchguard/searchguard_1/_restore?wait_for_completion=true","{ \"indices\": \"searchguard\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard_copy\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore all indices.
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/all", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/all/all_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","{ \"indices\": \"vulcangov\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","{ \"indices\": \"searchguard\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard_copy\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore a unknown snapshot
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/unknown-snapshot/_restore?wait_for_completion=true", "", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePostRequest("_snapshot/all/unknown-snapshot/_restore?wait_for_completion=true","{ \"indices\": \"the-unknown-index\" }", encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
    }

    @Test
    public void testSnapshotCheckWritePrivileges() throws Exception {
    
        final Settings settings = Settings.builder()
                .putList("path.repo", repositoryPath.getRoot().getAbsolutePath())
                .put("searchguard.enable_snapshot_restore_privilege", true)
                .put("searchguard.check_snapshot_restore_write_privileges", true)
                .build();
    
        setup(settings);
    
        try (TransportClient tc = getInternalTransportClient()) {
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            
            tc.admin().cluster().putRepository(new PutRepositoryRequest("vulcangov").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/vulcangov"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("vulcangov", "vulcangov_1").indices("vulcangov").includeGlobalState(true).waitForCompletion(true)).actionGet();
    
            tc.admin().cluster().putRepository(new PutRepositoryRequest("searchguard").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/searchguard"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("searchguard", "searchguard_1").indices("searchguard").includeGlobalState(false).waitForCompletion(true)).actionGet();
    
            tc.admin().cluster().putRepository(new PutRepositoryRequest("all").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/all"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("all", "all_1").indices("*").includeGlobalState(false).waitForCompletion(true)).actionGet();
    
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
            System.out.println(cur.getNodesMap());
        }
    
        RestHelper rh = nonSslRestHelper();
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/vulcangov", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/vulcangov/vulcangov_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_$1\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"include_global_state\": true, \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_with_global_state_$1\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","", encodeBasicHeader("worf", "worf")).getStatusCode());
        // Try to restore vulcangov index as searchguard index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"indices\": \"vulcangov\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore searchguard index.
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/searchguard", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/searchguard/searchguard_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/searchguard/searchguard_1/_restore?wait_for_completion=true","", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/searchguard/searchguard_1/_restore?wait_for_completion=true","{ \"indices\": \"searchguard\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard_copy\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore all indices.
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/all", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/all/all_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","{ \"indices\": \"vulcangov\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","{ \"indices\": \"searchguard\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard_copy\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore a unknown snapshot
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/unknown-snapshot/_restore?wait_for_completion=true", "", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Tests snapshot with write permissions (OK)
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_restore_1\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_restore_2a\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
    
        // Test snapshot with write permissions (FAIL)
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_no_restore_1\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_no_restore_2\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_no_restore_3\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_no_restore_4\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
    }

    @Test
    public void testSnapshotRestore() throws Exception {
    
        final Settings settings = Settings.builder()
                .putList("path.repo", repositoryPath.getRoot().getAbsolutePath())
                .put("searchguard.enable_snapshot_restore_privilege", true)
                .put("searchguard.check_snapshot_restore_write_privileges", true)
                .build();
    
        setup(Settings.EMPTY, new DynamicSgConfig().setSgActionGroups("sg_action_groups_packaged.yml"), settings, true);
    
        try (TransportClient tc = getInternalTransportClient()) {    
            tc.index(new IndexRequest("testsnap1").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("testsnap2").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("testsnap3").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("testsnap4").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("testsnap5").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("testsnap6").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            
            tc.admin().cluster().putRepository(new PutRepositoryRequest("bckrepo").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/bckrepo"))).actionGet();
        }
    
        RestHelper rh = nonSslRestHelper();        
        String putSnapshot =
        "{"+
          "\"indices\": \"testsnap1\","+
          "\"ignore_unavailable\": false,"+
          "\"include_global_state\": false"+
        "}";
        
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePutRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"?wait_for_completion=true&pretty", putSnapshot, encodeBasicHeader("snapresuser", "nagilum")).getStatusCode()); 
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePostRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"/_restore?wait_for_completion=true&pretty","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_$1\" }", encodeBasicHeader("snapresuser", "nagilum")).getStatusCode());
        
        putSnapshot =
        "{"+
          "\"indices\": \"searchguard\","+
          "\"ignore_unavailable\": false,"+
          "\"include_global_state\": false"+
        "}";
                
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePutRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"?wait_for_completion=true&pretty", putSnapshot, encodeBasicHeader("snapresuser", "nagilum")).getStatusCode()); 
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"/_restore?wait_for_completion=true&pretty","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_$1\" }", encodeBasicHeader("snapresuser", "nagilum")).getStatusCode());
              
        putSnapshot =
        "{"+
          "\"indices\": \"testsnap2\","+
          "\"ignore_unavailable\": false,"+
          "\"include_global_state\": true"+
        "}";
                        
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePutRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"?wait_for_completion=true&pretty", putSnapshot, encodeBasicHeader("snapresuser", "nagilum")).getStatusCode()); 
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"/_restore?wait_for_completion=true&pretty","{ \"include_global_state\": true, \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_$1\" }", encodeBasicHeader("snapresuser", "nagilum")).getStatusCode());
    }

    @Test
    public void testSpecialUsernames() throws Exception {
    
        setup();    
        RestHelper rh = nonSslRestHelper();
        
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("", encodeBasicHeader("bug.99", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, rh.executeGetRequest("", encodeBasicHeader("a", "b")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("", encodeBasicHeader("\"'+-,;_?*@<>!$%&/()=#", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("", encodeBasicHeader("§ÄÖÜäöüß", "nagilum")).getStatusCode());
    
    }

    @Test
    public void testXff() throws Exception {
    
        setup(Settings.EMPTY, new DynamicSgConfig().setSgConfig("sg_config_xff.yml"), Settings.EMPTY, true);
        RestHelper rh = nonSslRestHelper();
        HttpResponse resc = rh.executeGetRequest("_searchguard/authinfo", new BasicHeader("x-forwarded-for", "10.0.0.7"), encodeBasicHeader("worf", "worf"));
        Assert.assertEquals(200, resc.getStatusCode());
        Assert.assertTrue(resc.getBody().contains("10.0.0.7"));
    }

    @Test
    public void testDefaultInit() throws Exception {
        
        Settings b = Settings.builder().put(ConfigConstants.SEARCHGUARD_ALLOW_DEFAULT_INIT_SGINDEX, true).build();
        setup(Settings.EMPTY, new DynamicSgConfig(), b, false);
        
        RestHelper rh = nonSslRestHelper();
        HttpResponse res;
        Thread.sleep(5000);
        Assert.assertEquals(HttpStatus.SC_OK, (res = rh.executeGetRequest("_searchguard/license?pretty", encodeBasicHeader("admin", "admin"))).getStatusCode());
        System.out.println(res.getBody());
        assertContains(res, "*TRIAL*");
        assertNotContains(res, "*FULL*");
    }

    @Test
    public void testRegexExcludes() throws Exception {
        
        setup(Settings.EMPTY, new DynamicSgConfig(), Settings.EMPTY);

        try (TransportClient tc = getInternalTransportClient(this.clusterInfo, Settings.EMPTY)) {
            tc.index(new IndexRequest("indexa").type("type01").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"indexa\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("indexb").type("type01").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"indexb\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("isallowed").type("type01").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"isallowed\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("special").type("type01").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"special\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("alsonotallowed").type("type01").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"alsonotallowed\":1}", XContentType.JSON)).actionGet();
        }
        
        RestHelper rh = nonSslRestHelper();
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("index*/_search",encodeBasicHeader("rexclude", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("indexa/_search",encodeBasicHeader("rexclude", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("isallowed/_search",encodeBasicHeader("rexclude", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executeGetRequest("special/_search",encodeBasicHeader("rexclude", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executeGetRequest("alsonotallowed/_search",encodeBasicHeader("rexclude", "nagilum")).getStatusCode());
    }
    
    @Test
    public void testAliasResolution() throws Exception {

        final Settings settings = Settings.builder()
                .build();
        setup(settings);
        final RestHelper rh = nonSslRestHelper();

        try (TransportClient tc = getInternalTransportClient()) {                    
            tc.index(new IndexRequest("concreteindex-1").type("doc").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();                
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAliasAction(AliasActions.add().indices("concreteindex-1").alias("calias-1"))).actionGet();
            tc.index(new IndexRequest(".kibana-6").type("doc").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();                
            tc.admin().indices().aliases(new IndicesAliasesRequest().addAliasAction(AliasActions.add().indices(".kibana-6").alias(".kibana"))).actionGet();

        }

        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("calias-1/_search?pretty", encodeBasicHeader("aliastest", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("calias-*/_search?pretty", encodeBasicHeader("aliastest", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("*kibana/_search?pretty", encodeBasicHeader("aliastest", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest(".ki*ana/_search?pretty", encodeBasicHeader("aliastest", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest(".kibana/_search?pretty", encodeBasicHeader("aliastest", "nagilum")).getStatusCode());
    }
    
    @Test
    public void testMultiRoleSpan() throws Exception {
        
        setup();
        final RestHelper rh = nonSslRestHelper();

        try (TransportClient tc = getInternalTransportClient()) {    
            tc.index(new IndexRequest("mindex_1").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("mindex_2").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}", XContentType.JSON)).actionGet();
        }
        
        HttpResponse res = rh.executeGetRequest("/mindex_1,mindex_2/_search", encodeBasicHeader("mindex12", "nagilum"));
        System.out.println(res.getBody());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, res.getStatusCode());
        Assert.assertFalse(res.getBody().contains("\"content\":1"));
        Assert.assertFalse(res.getBody().contains("\"content\":2"));
        
        try (TransportClient tc = getInternalTransportClient()) {                                       
            tc.index(new IndexRequest("searchguard").type("sg").id("config").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("config", FileHelper.readYamlContent("sg_config_multirolespan.yml"))).actionGet();
   
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
        }
        
        res = rh.executeGetRequest("/mindex_1,mindex_2/_search", encodeBasicHeader("mindex12", "nagilum"));
        System.out.println(res.getBody());
        Assert.assertEquals(HttpStatus.SC_OK, res.getStatusCode());
        Assert.assertTrue(res.getBody().contains("\"content\":1"));
        Assert.assertTrue(res.getBody().contains("\"content\":2"));
        
    }
    
    @Test
    public void testMultiRoleSpan2() throws Exception {
        
        setup(Settings.EMPTY, new DynamicSgConfig().setSgConfig("sg_config_multirolespan.yml"), Settings.EMPTY);
        final RestHelper rh = nonSslRestHelper();

        try (TransportClient tc = getInternalTransportClient()) {                                       
            tc.index(new IndexRequest("mindex_1").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("mindex_2").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("mindex_3").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("mindex_4").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":2}", XContentType.JSON)).actionGet();
        }
        
        HttpResponse res = rh.executeGetRequest("/mindex_1,mindex_2/_search", encodeBasicHeader("mindex12", "nagilum"));
        Assert.assertEquals(HttpStatus.SC_OK, res.getStatusCode());
        
        res = rh.executeGetRequest("/mindex_1,mindex_3/_search", encodeBasicHeader("mindex12", "nagilum"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, res.getStatusCode());

        res = rh.executeGetRequest("/mindex_1,mindex_4/_search", encodeBasicHeader("mindex12", "nagilum"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, res.getStatusCode());
         
    }
    
    @Test
    public void testCCSIndexResolve() throws Exception {
        
        setup();
        final RestHelper rh = nonSslRestHelper();

        try (TransportClient tc = getInternalTransportClient()) {                                       
            tc.index(new IndexRequest(".abc-6").type("logs").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
        }
        
        HttpResponse res = rh.executeGetRequest("/*:.abc-6,.abc-6/_search", encodeBasicHeader("ccsresolv", "nagilum"));
        Assert.assertTrue(res.getBody(),res.getBody().contains("\"content\":1"));
        Assert.assertEquals(HttpStatus.SC_OK, res.getStatusCode());
    }

    @Test
    public void testSGUnderscore() throws Exception {
        
        setup();
        final RestHelper rh = nonSslRestHelper();
        
        HttpResponse res = rh.executePostRequest("abc_xyz_2018_05_24/logs/1", "{\"content\":1}", encodeBasicHeader("underscore", "nagilum"));
        
        res = rh.executeGetRequest("abc_xyz_2018_05_24/logs/1", encodeBasicHeader("underscore", "nagilum"));
        Assert.assertTrue(res.getBody(),res.getBody().contains("\"content\":1"));
        Assert.assertEquals(HttpStatus.SC_OK, res.getStatusCode());
        res = rh.executeGetRequest("abc_xyz_2018_05_24/_refresh", encodeBasicHeader("underscore", "nagilum"));
        Assert.assertEquals(HttpStatus.SC_OK, res.getStatusCode());
        res = rh.executeGetRequest("aaa_bbb_2018_05_24/_refresh", encodeBasicHeader("underscore", "nagilum"));
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, res.getStatusCode());
    }

}
