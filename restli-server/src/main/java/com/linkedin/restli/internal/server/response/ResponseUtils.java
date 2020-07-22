/*
   Copyright (c) 2015 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.restli.internal.server.response;


import com.linkedin.data.ByteString;
import com.linkedin.data.Data;
import com.linkedin.data.DataList;
import com.linkedin.data.DataMap;
import com.linkedin.data.DataMapBuilder;
import com.linkedin.data.codec.entitystream.StreamDataCodec;
import com.linkedin.data.schema.ArrayDataSchema;
import com.linkedin.data.schema.DataSchema;
import com.linkedin.data.schema.PrimitiveDataSchema;
import com.linkedin.data.schema.RecordDataSchema;
import com.linkedin.data.schema.TyperefDataSchema;
import com.linkedin.data.template.DataTemplateUtil;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.entitystream.EntityStream;
import com.linkedin.r2.message.rest.RestException;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.r2.message.rest.RestResponseBuilder;
import com.linkedin.r2.message.stream.StreamException;
import com.linkedin.r2.message.stream.StreamResponse;
import com.linkedin.r2.message.stream.StreamResponseBuilder;
import com.linkedin.r2.message.stream.entitystream.adapter.EntityStreamAdapters;
import com.linkedin.restli.common.ContentType;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.common.RestConstants;
import com.linkedin.restli.internal.common.CookieUtil;
import com.linkedin.restli.internal.server.RoutingResult;
import com.linkedin.restli.internal.server.ServerResourceContext;
import com.linkedin.restli.internal.server.model.ResourceModel;
import com.linkedin.restli.internal.server.util.AlternativeKeyCoercerException;
import com.linkedin.restli.internal.server.util.ArgumentUtils;
import com.linkedin.restli.internal.server.util.DataMapUtils;
import com.linkedin.restli.restspec.ResourceEntityType;
import com.linkedin.restli.server.RestLiServiceException;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Map;
import javax.activation.MimeTypeParseException;
import org.checkerframework.checker.units.qual.A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author mtagle
 */
public class ResponseUtils
{
  private static final Logger log = LoggerFactory.getLogger(ResponseUtils.class);
  /**
   * If needed, translate a given canonical key to its alternative format.
   *
   * @param canonicalKey the canonical key
   * @param routingResult the routing result
   * @return the canonical key if the request did not use or ask for alternative keys, the alternative key otherwise.
   */
  static Object translateCanonicalKeyToAlternativeKeyIfNeeded(Object canonicalKey, RoutingResult routingResult)
  {
    if (routingResult.getContext().hasParameter(RestConstants.ALT_KEY_PARAM))
    {
      String altKeyName = routingResult.getContext().getParameter(RestConstants.ALT_KEY_PARAM);
      ResourceModel resourceModel = routingResult.getResourceMethod().getResourceModel();
      try
      {
        return ArgumentUtils.translateToAlternativeKey(canonicalKey, altKeyName, resourceModel);
      }
      catch (AlternativeKeyCoercerException e)
      {
        throw new RestLiServiceException(HttpStatus.S_500_INTERNAL_SERVER_ERROR,
                String.format("Unexpected Error when coercing canonical key '%s' to alternative key type '%s'", canonicalKey, altKeyName),
                e);
      }
    }
    else
    {
      return canonicalKey;
    }
  }

  public static DataMap fillInDefaultOnRecord(RecordDataSchema schema, DataMap dataMap)
  {
    DataMap dataWithDefault = new DataMap(DataMapBuilder.getOptimumHashMapCapacityFromSize(dataMap.size()));
    dataWithDefault.putAll(dataMap);
    for (RecordDataSchema.Field field : schema.getFields())
    {
      DataSchema dataFieldSchema = field.getType();
      if (dataFieldSchema.getType() == DataSchema.Type.RECORD)
      {
        if (dataMap.containsKey(field.getName()) || field.getDefault() != null)
        {
          DataMap originalData = dataMap.containsKey(field.getName()) ? (DataMap) dataMap.get(field.getName()) : (DataMap) field.getDefault();
          dataWithDefault.put(field.getName(), fillInDefaultOnRecord((RecordDataSchema) dataFieldSchema, originalData));
        }
      }
      else if (dataFieldSchema.getType() == DataSchema.Type.ARRAY)
      {
        if (dataMap.containsKey(field.getName()) || field.getDefault() != null)
        {
          DataList originalData = dataMap.containsKey(field.getName()) ? (DataList) dataMap.get(field.getName()) : (DataList) field.getDefault();
          dataWithDefault.put(field.getName(), fillInDefaultOnArray((ArrayDataSchema) dataFieldSchema, originalData));
        }
      }
      else if (dataFieldSchema.getType() == DataSchema.Type.TYPEREF)
      {
        if (dataMap.containsKey(field.getName()) || field.getDefault() != null)
        {
          Object originalData = dataMap.containsKey(field.getName()) ? dataMap.get(field.getName()) : field.getDefault();
          dataWithDefault.put(field.getName(), fillInDefaultOnTyperef((TyperefDataSchema) dataFieldSchema, originalData));
        }
      }
      else if (!dataWithDefault.containsKey(field.getName()) && field.getDefault() != null)
      {
        dataWithDefault.put(field.getName(), field.getDefault());
      }
    }
    return dataWithDefault;
  }

  public static DataList fillInDefaultOnArray(ArrayDataSchema schema, DataList dataList)
  {
    DataSchema itemDataSchema = schema.getItems();
    DataList dataListWithDefault = new DataList();
    for (Object o : dataList)
    {
      if (itemDataSchema.getType() == DataSchema.Type.ARRAY)
      {
        dataListWithDefault.add(fillInDefaultOnArray((ArrayDataSchema) itemDataSchema, (DataList) o));
      }
      else if (itemDataSchema.getType() == DataSchema.Type.RECORD)
      {
        dataListWithDefault.add(fillInDefaultOnRecord((RecordDataSchema) itemDataSchema, (DataMap) o));
      }
      else if (itemDataSchema.getType() == DataSchema.Type.TYPEREF)
      {
        dataListWithDefault.add(fillInDefaultOnTyperef((TyperefDataSchema) itemDataSchema, o));
      }
      else
      {
        dataListWithDefault.add(o);
      }
    }
    return dataListWithDefault;
  }

  public static Object fillInDefaultOnTyperef(TyperefDataSchema typerefDataSchema, Object data)
  {
    DataSchema dataSchema = typerefDataSchema.getDereferencedDataSchema();
    if (dataSchema.getType() == DataSchema.Type.RECORD)
    {
      DataMap dataMap = (DataMap) data;
      return (Object) fillInDefaultOnRecord((RecordDataSchema)dataSchema, dataMap);
    }
    else if (dataSchema.getType() == DataSchema.Type.TYPEREF)
    {
      return fillInDefaultOnTyperef((TyperefDataSchema) dataSchema, data);
    }
    else
    {
      return data;
    }
  }

  public static DataMap fillInDefaultValues(DataSchema dataSchema, DataMap dataMap)
  {
    if (dataSchema.getType() == DataSchema.Type.RECORD)
    {
      return fillInDefaultOnRecord((RecordDataSchema) dataSchema, dataMap);
    }
    return dataMap;
  }

  public static RestResponse buildResponse(RoutingResult routingResult, RestLiResponse restLiResponse)
  {
    RestResponseBuilder builder = new RestResponseBuilder()
        .setHeaders(restLiResponse.getHeaders())
        .setCookies(CookieUtil.encodeSetCookies(restLiResponse.getCookies()))
        .setStatus(restLiResponse.getStatus().getCode());

    ServerResourceContext context = routingResult.getContext();
    ResourceEntityType resourceEntityType = routingResult.getResourceMethod()
                                                         .getResourceModel()
                                                         .getResourceEntityType();
    if (restLiResponse.hasData() && ResourceEntityType.STRUCTURED_DATA == resourceEntityType)
    {
      DataMap dataMap = restLiResponse.getDataMap();
      String mimeType = context.getResponseMimeType();
      URI requestUri = context.getRequestURI();
      Map<String, String> requestHeaders = context.getRequestHeaders();
      builder = encodeResult(mimeType, requestUri, requestHeaders, builder, dataMap);
    }
    return builder.build();
  }

  private static RestResponseBuilder encodeResult(String mimeType,
      URI requestUri,
      Map<String, String> requestHeaders,
      RestResponseBuilder builder,
      DataMap dataMap)
  {
    try
    {
      ContentType type = ContentType.getResponseContentType(mimeType, requestUri, requestHeaders).orElseThrow(
          () -> new RestLiServiceException(HttpStatus.S_406_NOT_ACCEPTABLE,
              "Requested mime type for encoding is not supported. Mimetype: " + mimeType));
      assert type != null;
      builder.setHeader(RestConstants.HEADER_CONTENT_TYPE, type.getHeaderKey());
      // Use unsafe wrap to avoid copying the bytes when request builder creates ByteString.
      builder.setEntity(ByteString.unsafeWrap(DataMapUtils.mapToBytes(dataMap, type.getCodec())));
    }
    catch (MimeTypeParseException e)
    {
      throw new RestLiServiceException(HttpStatus.S_406_NOT_ACCEPTABLE, "Invalid mime type: " + mimeType);
    }

    return builder;
  }

  public static RestException buildRestException(RestLiResponseException restLiResponseException)
  {
    return buildRestException(restLiResponseException, true);
  }

  public static RestException buildRestException(RestLiResponseException restLiResponseException, boolean writableStackTrace)
  {
    RestLiResponse restLiResponse = restLiResponseException.getRestLiResponse();
    RestResponseBuilder responseBuilder = new RestResponseBuilder()
        .setHeaders(restLiResponse.getHeaders())
        .setCookies(CookieUtil.encodeSetCookies(restLiResponse.getCookies()))
        .setStatus(restLiResponse.getStatus() == null ? HttpStatus.S_500_INTERNAL_SERVER_ERROR.getCode()
            : restLiResponse.getStatus().getCode());

    if (restLiResponse.hasData())
    {
      DataMap dataMap = restLiResponse.getDataMap();
      ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
      DataMapUtils.write(dataMap, baos, responseBuilder.getHeaders());
      responseBuilder.setEntity(ByteString.unsafeWrap(baos.toByteArray()));
    }

    RestResponse restResponse = responseBuilder.build();
    Throwable cause = restLiResponseException.getCause();
    return new RestException(restResponse, cause==null ? null : cause.toString(), cause, writableStackTrace);
  }

  public static StreamException buildStreamException(RestLiResponseException restLiResponseException, StreamDataCodec codec)
  {
    RestLiResponse restLiResponse = restLiResponseException.getRestLiResponse();
    StreamResponseBuilder responseBuilder = new StreamResponseBuilder()
        .setHeaders(restLiResponse.getHeaders())
        .setCookies(CookieUtil.encodeSetCookies(restLiResponse.getCookies()))
        .setStatus(restLiResponse.getStatus() == null ? HttpStatus.S_500_INTERNAL_SERVER_ERROR.getCode()
            : restLiResponse.getStatus().getCode());

    EntityStream<ByteString> entityStream = codec.encodeMap(restLiResponse.getDataMap());
    StreamResponse response = responseBuilder.build(EntityStreamAdapters.fromGenericEntityStream(entityStream));
    return new StreamException(response, restLiResponseException.getCause());
  }
}
