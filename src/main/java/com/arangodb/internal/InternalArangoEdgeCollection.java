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

package com.arangodb.internal;

import java.util.HashMap;
import java.util.Map;

import com.arangodb.entity.DocumentField;
import com.arangodb.entity.EdgeEntity;
import com.arangodb.entity.EdgeUpdateEntity;
import com.arangodb.internal.ArangoExecutor.ResponseDeserializer;
import com.arangodb.internal.velocystream.internal.Connection;
import com.arangodb.model.DocumentReadOptions;
import com.arangodb.model.EdgeCreateOptions;
import com.arangodb.model.EdgeDeleteOptions;
import com.arangodb.model.EdgeReplaceOptions;
import com.arangodb.model.EdgeUpdateOptions;
import com.arangodb.util.ArangoSerializer;
import com.arangodb.velocypack.VPackSlice;
import com.arangodb.velocypack.exception.VPackException;
import com.arangodb.velocystream.Request;
import com.arangodb.velocystream.RequestType;
import com.arangodb.velocystream.Response;

/**
 * @author Mark - mark at arangodb.com
 *
 */
public class InternalArangoEdgeCollection<A extends InternalArangoDB<E, R, C>, D extends InternalArangoDatabase<A, E, R, C>, G extends InternalArangoGraph<A, D, E, R, C>, E extends ArangoExecutor, R, C extends Connection>
		extends ArangoExecuteable<E, R, C> {

	private final G graph;
	private final String name;

	public InternalArangoEdgeCollection(final G graph, final String name) {
		super(graph.executor(), graph.util());
		this.graph = graph;
		this.name = name;
	}

	public G graph() {
		return graph;
	}

	public String name() {
		return name;
	}

	protected <T> Request insertEdgeRequest(final T value, final EdgeCreateOptions options) {
		final Request request = new Request(graph.db().name(), RequestType.POST,
				executor.createPath(ArangoDBConstants.PATH_API_GHARIAL, graph.name(), ArangoDBConstants.EDGE, name));
		final EdgeCreateOptions params = (options != null ? options : new EdgeCreateOptions());
		request.putQueryParam(ArangoDBConstants.WAIT_FOR_SYNC, params.getWaitForSync());
		request.setBody(util().serialize(value));
		return request;
	}

	protected <T> ResponseDeserializer<EdgeEntity> insertEdgeResponseDeserializer(final T value) {
		return new ResponseDeserializer<EdgeEntity>() {
			@Override
			public EdgeEntity deserialize(final Response response) throws VPackException {
				final VPackSlice body = response.getBody().get(ArangoDBConstants.EDGE);
				final EdgeEntity doc = util().deserialize(body, EdgeEntity.class);
				final Map<DocumentField.Type, String> values = new HashMap<DocumentField.Type, String>();
				values.put(DocumentField.Type.ID, doc.getId());
				values.put(DocumentField.Type.KEY, doc.getKey());
				values.put(DocumentField.Type.REV, doc.getRev());
				executor.documentCache().setValues(value, values);
				return doc;
			}
		};
	}

	protected Request getEdgeRequest(final String key, final DocumentReadOptions options) {
		final Request request = new Request(graph.db().name(), RequestType.GET,
				executor.createPath(ArangoDBConstants.PATH_API_GHARIAL, graph.name(), ArangoDBConstants.EDGE,
					executor.createDocumentHandle(name, key)));
		final DocumentReadOptions params = (options != null ? options : new DocumentReadOptions());
		request.putHeaderParam(ArangoDBConstants.IF_NONE_MATCH, params.getIfNoneMatch());
		request.putHeaderParam(ArangoDBConstants.IF_MATCH, params.getIfMatch());
		return request;
	}

	protected <T> ResponseDeserializer<T> getEdgeResponseDeserializer(final Class<T> type) {
		return new ResponseDeserializer<T>() {
			@Override
			public T deserialize(final Response response) throws VPackException {
				return util().deserialize(response.getBody().get(ArangoDBConstants.EDGE), type);
			}
		};
	}

	protected <T> Request replaceEdgeRequest(final String key, final T value, final EdgeReplaceOptions options) {
		final Request request = new Request(graph.db().name(), RequestType.PUT,
				executor.createPath(ArangoDBConstants.PATH_API_GHARIAL, graph.name(), ArangoDBConstants.EDGE,
					executor.createDocumentHandle(name, key)));
		final EdgeReplaceOptions params = (options != null ? options : new EdgeReplaceOptions());
		request.putQueryParam(ArangoDBConstants.WAIT_FOR_SYNC, params.getWaitForSync());
		request.putHeaderParam(ArangoDBConstants.IF_MATCH, params.getIfMatch());
		request.setBody(util().serialize(value));
		return request;
	}

	protected <T> ResponseDeserializer<EdgeUpdateEntity> replaceEdgeResponseDeserializer(final T value) {
		return new ResponseDeserializer<EdgeUpdateEntity>() {
			@Override
			public EdgeUpdateEntity deserialize(final Response response) throws VPackException {
				final VPackSlice body = response.getBody().get(ArangoDBConstants.EDGE);
				final EdgeUpdateEntity doc = util().deserialize(body, EdgeUpdateEntity.class);
				final Map<DocumentField.Type, String> values = new HashMap<DocumentField.Type, String>();
				values.put(DocumentField.Type.REV, doc.getRev());
				executor.documentCache().setValues(value, values);
				return doc;
			}
		};
	}

	protected <T> Request updateEdgeRequest(final String key, final T value, final EdgeUpdateOptions options) {
		final Request request;
		request = new Request(graph.db().name(), RequestType.PATCH,
				executor.createPath(ArangoDBConstants.PATH_API_GHARIAL, graph.name(), ArangoDBConstants.EDGE,
					executor.createDocumentHandle(name, key)));
		final EdgeUpdateOptions params = (options != null ? options : new EdgeUpdateOptions());
		request.putQueryParam(ArangoDBConstants.KEEP_NULL, params.getKeepNull());
		request.putQueryParam(ArangoDBConstants.WAIT_FOR_SYNC, params.getWaitForSync());
		request.putHeaderParam(ArangoDBConstants.IF_MATCH, params.getIfMatch());
		request.setBody(util().serialize(value, new ArangoSerializer.Options().serializeNullValues(true)));
		return request;
	}

	protected <T> ResponseDeserializer<EdgeUpdateEntity> updateEdgeResponseDeserializer(final T value) {
		return new ResponseDeserializer<EdgeUpdateEntity>() {
			@Override
			public EdgeUpdateEntity deserialize(final Response response) throws VPackException {
				final VPackSlice body = response.getBody().get(ArangoDBConstants.EDGE);
				return util().deserialize(body, EdgeUpdateEntity.class);
			}
		};
	}

	protected Request deleteEdgeRequest(final String key, final EdgeDeleteOptions options) {
		final Request request = new Request(graph.db().name(), RequestType.DELETE,
				executor.createPath(ArangoDBConstants.PATH_API_GHARIAL, graph.name(), ArangoDBConstants.EDGE,
					executor.createDocumentHandle(name, key)));
		final EdgeDeleteOptions params = (options != null ? options : new EdgeDeleteOptions());
		request.putQueryParam(ArangoDBConstants.WAIT_FOR_SYNC, params.getWaitForSync());
		request.putHeaderParam(ArangoDBConstants.IF_MATCH, params.getIfMatch());
		return request;
	}

}
