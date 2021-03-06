/*
 * DISCLAIMER
 *
 * Copyright 2016 ArangoDB GmbH, Cologne, Germany
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
 * Copyright holder is ArangoDB GmbH, Cologne, Germany
 */

package com.arangodb.internal.http;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.net.ssl.SSLContext;

import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.arangodb.ArangoDBException;
import com.arangodb.Protocol;
import com.arangodb.entity.ErrorEntity;
import com.arangodb.internal.ArangoDBConstants;
import com.arangodb.internal.Host;
import com.arangodb.internal.HostHandler;
import com.arangodb.internal.util.CURLLogger;
import com.arangodb.internal.util.IOUtils;
import com.arangodb.util.ArangoSerialization;
import com.arangodb.util.ArangoSerializer.Options;
import com.arangodb.velocypack.VPackSlice;
import com.arangodb.velocypack.exception.VPackParserException;
import com.arangodb.velocystream.Request;
import com.arangodb.velocystream.Response;

/**
 * @author Mark - mark at arangodb.com
 *
 */
public class HttpCommunication {

	public static class Builder {

		private final HostHandler hostHandler;
		private final Protocol protocol;
		private Integer timeout;
		private String user;
		private String password;
		private Boolean useSsl;
		private SSLContext sslContext;
		private Integer maxConnections;

		public Builder(final HostHandler hostHandler, final Protocol protocol) {
			super();
			this.hostHandler = hostHandler;
			this.protocol = protocol;
		}

		public Builder timeout(final Integer timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder user(final String user) {
			this.user = user;
			return this;
		}

		public Builder password(final String password) {
			this.password = password;
			return this;
		}

		public Builder useSsl(final Boolean useSsl) {
			this.useSsl = useSsl;
			return this;
		}

		public Builder sslContext(final SSLContext sslContext) {
			this.sslContext = sslContext;
			return this;
		}

		public Builder maxConnections(final Integer maxConnections) {
			this.maxConnections = maxConnections;
			return this;
		}

		public HttpCommunication build(final ArangoSerialization util) {
			return new HttpCommunication(timeout, user, password, useSsl, sslContext, util, hostHandler, maxConnections,
					protocol);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(HttpCommunication.class);
	private static final ContentType CONTENT_TYPE_APPLICATION_JSON_UTF8 = ContentType.create("application/json",
		"utf-8");
	private static final ContentType CONTENT_TYPE_VPACK = ContentType.create("application/x-velocypack");
	private static final int ERROR_STATUS = 300;
	private final PoolingHttpClientConnectionManager cm;
	private final CloseableHttpClient client;
	private final String user;
	private final String password;
	private final ArangoSerialization util;
	private final HostHandler hostHandler;
	private final Boolean useSsl;
	private final Protocol contentType;

	private HttpCommunication(final Integer timeout, final String user, final String password, final Boolean useSsl,
		final SSLContext sslContext, final ArangoSerialization util, final HostHandler hostHandler,
		final Integer maxConnections, final Protocol contentType) {
		super();
		this.user = user;
		this.password = password;
		this.useSsl = useSsl;
		this.util = util;
		this.hostHandler = hostHandler;
		this.contentType = contentType;
		final RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder
				.<ConnectionSocketFactory> create();
		if (useSsl != null && useSsl) {
			if (sslContext != null) {
				registryBuilder.register("https", new SSLConnectionSocketFactory(sslContext));
			} else {
				registryBuilder.register("https", new SSLConnectionSocketFactory(SSLContexts.createSystemDefault()));
			}
		} else {
			registryBuilder.register("http", new PlainConnectionSocketFactory());
		}
		cm = new PoolingHttpClientConnectionManager(registryBuilder.build());
		final int connections = maxConnections != null ? Math.max(1, maxConnections)
				: ArangoDBConstants.MAX_CONNECTIONS_HTTP_DEFAULT;
		cm.setDefaultMaxPerRoute(connections);
		cm.setMaxTotal(connections);
		final RequestConfig.Builder requestConfig = RequestConfig.custom();
		if (timeout != null && timeout >= 0) {
			requestConfig.setConnectTimeout(timeout);
			requestConfig.setConnectionRequestTimeout(timeout);
			requestConfig.setSocketTimeout(timeout);
		}
		final ConnectionKeepAliveStrategy keepAliveStrategy = new ConnectionKeepAliveStrategy() {
			@Override
			public long getKeepAliveDuration(final HttpResponse response, final HttpContext context) {
				return HttpCommunication.this.getKeepAliveDuration(response);
			}
		};
		final HttpClientBuilder builder = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig.build())
				.setConnectionManager(cm).setKeepAliveStrategy(keepAliveStrategy)
				.setRetryHandler(new DefaultHttpRequestRetryHandler());
		client = builder.build();
	}

	private long getKeepAliveDuration(final HttpResponse response) {
		final HeaderElementIterator it = new BasicHeaderElementIterator(response.headerIterator(HTTP.CONN_KEEP_ALIVE));
		while (it.hasNext()) {
			final HeaderElement he = it.nextElement();
			final String param = he.getName();
			final String value = he.getValue();
			if (value != null && "timeout".equalsIgnoreCase(param)) {
				try {
					return Long.parseLong(value) * 1000L;
				} catch (final NumberFormatException ignore) {
				}
			}
		}
		return 30L * 1000L;
	}

	public void disconnect() {
		cm.shutdown();
		try {
			client.close();
		} catch (final IOException e) {
		}
	}

	public Response execute(final Request request) throws ArangoDBException, IOException {
		Host host = hostHandler.get();
		while (true) {
			try {
				final String url = buildUrl(buildBaseUrl(host), request);
				final HttpRequestBase httpRequest = buildHttpRequestBase(request, url);
				httpRequest.setHeader("User-Agent",
					"Mozilla/5.0 (compatible; ArangoDB-JavaDriver/1.1; +http://mt.orz.at/)");
				if (contentType == Protocol.HTTP_VPACK) {
					httpRequest.setHeader("Accept", "application/x-velocypack");
				}
				addHeader(request, httpRequest);
				final Credentials credentials = addCredentials(httpRequest);
				if (LOGGER.isDebugEnabled()) {
					CURLLogger.log(url, request, credentials, util);
				}
				Response response;
				response = buildResponse(client.execute(httpRequest));
				checkError(response);
				hostHandler.success();
				return response;
			} catch (final SocketException e) {
				hostHandler.fail();
				final Host failedHost = host;
				host = hostHandler.change();
				if (host != null) {
					LOGGER.warn(String.format("Could not connect to %s. Try connecting to %s", failedHost, host));
				} else {
					throw e;
				}
			}
		}
	}

	private HttpRequestBase buildHttpRequestBase(final Request request, final String url) {
		final HttpRequestBase httpRequest;
		switch (request.getRequestType()) {
		case POST:
			httpRequest = requestWithBody(new HttpPost(url), request);
			break;
		case PUT:
			httpRequest = requestWithBody(new HttpPut(url), request);
			break;
		case PATCH:
			httpRequest = requestWithBody(new HttpPatch(url), request);
			break;
		case DELETE:
			httpRequest = requestWithBody(new HttpDeleteWithBody(url), request);
			break;
		case HEAD:
			httpRequest = new HttpHead(url);
			break;
		case GET:
		default:
			httpRequest = new HttpGet(url);
			break;
		}
		return httpRequest;
	}

	private HttpRequestBase requestWithBody(final HttpEntityEnclosingRequestBase httpRequest, final Request request) {
		final VPackSlice body = request.getBody();
		if (body != null) {
			if (contentType == Protocol.HTTP_VPACK) {
				httpRequest.setEntity(new ByteArrayEntity(
						Arrays.copyOfRange(body.getBuffer(), body.getStart(), body.getStart() + body.getByteSize()),
						CONTENT_TYPE_VPACK));
			} else {
				httpRequest.setEntity(new StringEntity(body.toString(), CONTENT_TYPE_APPLICATION_JSON_UTF8));
			}
		}
		return httpRequest;
	}

	private String buildBaseUrl(final Host host) {
		return (useSsl != null && useSsl ? "https://" : "http://") + host.getHost() + ":" + host.getPort();
	}

	private static String buildUrl(final String baseUrl, final Request request) throws UnsupportedEncodingException {
		final StringBuilder sb = new StringBuilder().append(baseUrl);
		final String database = request.getDatabase();
		if (database != null && !database.isEmpty()) {
			sb.append("/_db/").append(database);
		}
		sb.append(request.getRequest());
		if (!request.getQueryParam().isEmpty()) {
			if (request.getRequest().contains("?")) {
				sb.append("&");
			} else {
				sb.append("?");
			}
			final String paramString = URLEncodedUtils.format(toList(request.getQueryParam()), "utf-8");
			sb.append(paramString);
		}
		return sb.toString();
	}

	private static List<NameValuePair> toList(final Map<String, String> parameters) {
		final ArrayList<NameValuePair> paramList = new ArrayList<NameValuePair>(parameters.size());
		for (final Entry<String, String> param : parameters.entrySet()) {
			if (param.getValue() != null) {
				paramList.add(new BasicNameValuePair(param.getKey(), param.getValue().toString()));
			}
		}
		return paramList;
	}

	private static void addHeader(final Request request, final HttpRequestBase httpRequest) {
		for (final Entry<String, String> header : request.getHeaderParam().entrySet()) {
			httpRequest.addHeader(header.getKey(), header.getValue());
		}
	}

	public Credentials addCredentials(final HttpRequestBase httpRequest) {
		Credentials credentials = null;
		if (user != null) {
			credentials = new UsernamePasswordCredentials(user, password != null ? password : "");
			try {
				httpRequest.addHeader(new BasicScheme().authenticate(credentials, httpRequest, null));
			} catch (final AuthenticationException e) {
				throw new ArangoDBException(e);
			}
		}
		return credentials;
	}

	public Response buildResponse(final CloseableHttpResponse httpResponse)
			throws UnsupportedOperationException, IOException {
		final Response response = new Response();
		response.setResponseCode(httpResponse.getStatusLine().getStatusCode());
		final HttpEntity entity = httpResponse.getEntity();
		if (entity != null && entity.getContent() != null) {
			if (contentType == Protocol.HTTP_VPACK) {
				final byte[] content = IOUtils.toByteArray(entity.getContent());
				if (content.length > 0) {
					response.setBody(new VPackSlice(content));
				}
			} else {
				final String content = IOUtils.toString(entity.getContent());
				if (!content.isEmpty()) {
					response.setBody(
						util.serialize(content, new Options().stringAsJson(true).serializeNullValues(true)));
				}
			}
		}
		return response;
	}

	protected void checkError(final Response response) throws ArangoDBException {
		try {
			if (response.getResponseCode() >= ERROR_STATUS) {
				if (response.getBody() != null) {
					final ErrorEntity errorEntity = util.deserialize(response.getBody(), ErrorEntity.class);
					throw new ArangoDBException(errorEntity);
				} else {
					throw new ArangoDBException(String.format("Response Code: %s", response.getResponseCode()));
				}
			}
		} catch (final VPackParserException e) {
			throw new ArangoDBException(e);
		}
	}

}
