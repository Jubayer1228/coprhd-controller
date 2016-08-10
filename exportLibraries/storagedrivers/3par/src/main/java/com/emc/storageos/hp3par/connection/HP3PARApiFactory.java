/*
 * Copyright 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.hp3par.connection;

import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.security.SecureRandom;
import java.security.cert.CertificateException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpMethodRetryHandler;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.log4j.BasicConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.hp3par.command.CPGCommandResult;
import com.emc.storageos.hp3par.command.HostCommandResult;
import com.emc.storageos.hp3par.command.HostMember;
import com.emc.storageos.hp3par.command.HostSetDetailsCommandResult;
import com.emc.storageos.hp3par.command.PortStatisticsCommandResult;
import com.emc.storageos.hp3par.command.SystemCommandResult;
import com.emc.storageos.hp3par.command.VirtualLunsList;
import com.emc.storageos.hp3par.command.VolumeDetailsCommandResult;
import com.emc.storageos.hp3par.impl.HP3PARApi;
import com.emc.storageos.hp3par.impl.HP3PARException;
import com.emc.storageos.hp3par.utils.CompleteError;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandler;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.client.apache.ApacheHttpClient;
import com.sun.jersey.client.apache.ApacheHttpClientHandler;
import com.sun.jersey.client.urlconnection.HTTPSProperties;
import com.sun.jersey.client.urlconnection.URLConnectionClientHandler;

/*
 * HP 3PAR API client factory
 */
public class HP3PARApiFactory {
    private Logger _log = LoggerFactory.getLogger(HP3PARApiFactory.class);
    private static final int DEFAULT_MAX_CONN = 300;
    private static final int DEFAULT_MAX_CONN_PER_HOST = 100;
    private static final int DEFAULT_CONN_TIMEOUT = 1000 * 30;
    private static final int DEFAULT_CONN_MGR_TIMEOUT = 1000 * 60;
    private static final int DEFAULT_SOCKET_CONN_TIMEOUT = 1000 * 60 * 60;

    private int _maxConn = DEFAULT_MAX_CONN;
    private int _maxConnPerHost = DEFAULT_MAX_CONN_PER_HOST;
    private int _connTimeout = DEFAULT_CONN_TIMEOUT;
    private int _socketConnTimeout = DEFAULT_SOCKET_CONN_TIMEOUT;
    private int connManagerTimeout = DEFAULT_CONN_MGR_TIMEOUT;

//    private ApacheHttpClientHandler _clientHandler; ///--------------------
    private ConcurrentMap<String, HP3PARApi> _clientMap;
    private MultiThreadedHttpConnectionManager _connectionManager;

    /**
     * Maximum number of outstanding connections
     *
     * @param maxConn
     */
    public void setMaxConnections(int maxConn) {
        _maxConn = maxConn;
    }

    /**
     * Maximum number of outstanding connections per host
     *
     * @param maxConnPerHost
     */
    public void setMaxConnectionsPerHost(int maxConnPerHost) {
        _maxConnPerHost = maxConnPerHost;
    }

    /**
     * Connection timeout
     *
     * @param connectionTimeoutMs
     */
    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        _connTimeout = connectionTimeoutMs;
    }

    /**
     * Socket connection timeout
     *
     * @param connectionTimeoutMs
     */
    public void setSocketConnectionTimeoutMs(int connectionTimeoutMs) {
        _socketConnTimeout = connectionTimeoutMs;
    }

    /**
     * @param connManagerTimeout the connManagerTimeout to set
     */
    public void setConnManagerTimeout(int connManagerTimeout) {
        this.connManagerTimeout = connManagerTimeout;
    }

    /**
     * Initialize HTTP client
     */
    public void init() {
        _log.info("3PARDriver:HP3PARApiFactory init enter");
        _clientMap = new ConcurrentHashMap<String, HP3PARApi>();

        HttpConnectionManagerParams params = new HttpConnectionManagerParams();
        params.setDefaultMaxConnectionsPerHost(_maxConnPerHost);
        params.setMaxTotalConnections(_maxConn);
        params.setTcpNoDelay(true);
        params.setConnectionTimeout(_connTimeout);
        params.setSoTimeout(_socketConnTimeout);

        _connectionManager = new MultiThreadedHttpConnectionManager();
        _connectionManager.setParams(params);
        _connectionManager.closeIdleConnections(0);  // close idle connections immediately

        HttpClient client = new HttpClient(_connectionManager);
        client.getParams().setConnectionManagerTimeout(connManagerTimeout);
        client.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, new HttpMethodRetryHandler() {
            @Override
            public boolean retryMethod(HttpMethod httpMethod, IOException e, int i) {
                return false;
            }
        });
//        _clientHandler = new ApacheHttpClientHandler(client); ///----------------

        Protocol.registerProtocol("https", new Protocol("https", new NonValidatingSocketFactory(), 8080));
    }

    /**
     * shutdown http connection manager.
     */
    protected void shutdown() {
        _connectionManager.shutdown();
    }
    
    
    public ClientConfig configureClient() throws NoSuchAlgorithmException,
    KeyManagementException {

    	TrustManager[] certs = new TrustManager[] { new X509TrustManager() {
    		@Override
    		public X509Certificate[] getAcceptedIssuers() {
    			return null;
    		}

    		@Override
    		public void checkServerTrusted(X509Certificate[] chain,
    				String authType) throws CertificateException {
    		}

    		@Override
    		public void checkClientTrusted(X509Certificate[] chain,
    				String authType) throws CertificateException {
    		}
    	} };
    	SSLContext ctx = null;
    	try {
    		ctx = SSLContext.getInstance("TLS");
    		ctx.init(null, certs, new SecureRandom());
    	} catch (java.security.GeneralSecurityException ex) {
    	}
    	HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());

    	ClientConfig config = new DefaultClientConfig();
    	try {
    		config.getProperties().put(
    				HTTPSProperties.PROPERTY_HTTPS_PROPERTIES,
    				new HTTPSProperties(new HostnameVerifier() {
    					@Override
    					public boolean verify(String hostname,
    							SSLSession session) {
    						return true;
    					}
    				}, ctx));
    	} catch (Exception e) {
    	}
    	config.getFeatures().put(JSONConfiguration.FEATURE_POJO_MAPPING, Boolean.TRUE);
    	return config;
    }
    
    /**
     * Create HP3PAR API client
     * 
     * @param endpoint
     * @param username
     * @param password
     * @return api client
     * @throws HP3PARException 
     */
    public HP3PARApi getRESTClient(URI endpoint, String username, String password) throws HP3PARException {
        try {
            _log.info("3PARDriver:getRESTClient");
            // key=uri+user+pass to make unique, value=HP3PARApi object
            HP3PARApi hp3parApi = _clientMap.get(endpoint.toString() + ":" + username + ":" + password);
            if (hp3parApi == null) {
                _log.info("3PARDriver:getRESTClient1 hp3parApi null");
               
                ClientHandler handler = new URLConnectionClientHandler();
                Client connClient = new Client(handler,configureClient());
                connClient.addFilter(new HTTPBasicAuthFilter(username, password));
                RESTClient restClient = new RESTClient(connClient);
                
//                Client jerseyClient = new ApacheHttpClient(_clientHandler);
//                jerseyClient.addFilter(new HTTPBasicAuthFilter(username, password));
//                RESTClient restClient = new RESTClient(jerseyClient);
                hp3parApi = new HP3PARApi(endpoint, restClient, username, password);
                _clientMap.putIfAbsent(endpoint.toString() + ":" + username + ":" + password, hp3parApi);
            }
            return hp3parApi;
        } catch (Exception e) {
            e.printStackTrace();
            _log.error("3PARDriver:getRESTClient Error in getting RESTclient");
            throw new HP3PARException(e.toString());
        }
    }
    
 // Sample direct program
    public static void main(String[] args) {
        System.out.println("starting HP3PAR main");
        try {
        URI uri = URI.create(String.format("https://10.247.143.100:8080/api/v1/credentials"));
        HP3PARApiFactory factory = new HP3PARApiFactory();
        factory.setConnectionTimeoutMs(30000*4);
        factory.setConnManagerTimeout(60000*4);
        factory.setSocketConnectionTimeoutMs(7200000*4);
        BasicConfigurator.configure();
        factory.init();
        HP3PARApi hp3parApi = factory.getRESTClient(uri, "superme", "superme");
        
        String authToken = hp3parApi.getAuthToken("superme", "superme");
        System.out.println(authToken);
        
//        hp3parApi.verifyUserRole("test2");
        SystemCommandResult sysRes = hp3parApi.getSystemDetails();
//        System.out.println(sysRes.toString());
//        CPGCommandResult cpgRes = hp3parApi.getAllCPGDetails();
//        System.out.println(cpgRes.toString());
//        hp3parApi.getPortDetails();
//        PortStatisticsCommandResult portStatRes = hp3parApi.getPortStatisticsDetail();
        
        String vol = "One_Thin21"; 
        hp3parApi.createVolume(vol, "One", true, false, (long)1024);
//        hp3parApi.createVlun(vol, -1, "myhost", "1:1:1");
//        hp3parApi.expandVolume(vol, (long)2048);
//        hp3parApi.getCPGDetails("One");
//        hp3parApi.deleteVolume(vol);
        
//        HostCommandResult hostRes = hp3parApi.getAllHostDetails();
//        HostSetDetailsCommandResult hostsetRes = hp3parApi.getHostSetDetails("MyCluster333");
//        boolean present = false;
//        for (int index = 0; index < hostsetRes.getSetmembers().size(); index++) {
//            if ("myhost1".compareTo(hostsetRes.getSetmembers().get(index)) == 0) {
//                present = true;
//                break;
//            }
//        }
//        
//        if (present == false) {
//            // update cluster with this host
//            hp3parApi.updateHostSet("MyCluster333", "myhost1");
//        }
        
//        HostMember hostRes = hp3parApi.getHostDetails("LGLBW015");
//        VirtualLunsList vlunRes = hp3parApi.getAllVlunDetails();
//        hp3parApi.deleteVlun("One_Thin30", "10", "LGLOE199", "1:1:1");
//        ArrayList<String> portIds = new ArrayList<>();
//        portIds.add("1111111111111114");
//        portIds.add("1111111111111115");
//        hp3parApi.createHost("myhostot7", portIds, 4);
//        hp3parApi.createtHostSet("mycluster2", "myhost1");
        
//        hp3parApi.updateHost("mydummy1", portIds);
        
        
        int a=0; a++; System.out.println(a);
        } catch (Exception e) {
            System.out.println("ERRRRRRROR");
            System.out.println(e);
            System.out.println(CompleteError.getStackTrace(e));
            e.printStackTrace();
        }
   } //end main
}
