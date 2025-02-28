/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.seatunnel.spark.feishu

import scala.collection.mutable.ArrayBuffer

import com.alibaba.fastjson.{JSON, JSONArray, JSONObject}
import org.apache.http.{HttpEntity, HttpHeaders}
import org.apache.http.client.methods.{CloseableHttpResponse, RequestBuilder}
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{DataTypes, StructField, StructType}
import org.slf4j.{Logger, LoggerFactory}

class FeishuClient(appId: String, appSecret: String) {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  def getToken: String = {
    val url = Config.TOKEN_URL.format(appId, appSecret)
    val result = this.requestFeishuApi(url, null)
    logger.info(s"Request token and get result $result")
    result.getString(Config.TENANT_ACCESS_TOKEN)
  }

  def getSheetId(sheetToken: String, sheetNum: Int): String = {
    val url = Config.META_INFO_URL.format(sheetToken)
    val data = this.requestFeishuApiAndGetData(url)
    if (null == data) {
      throw new RuntimeException(
        "Did not get any sheet in Feishu, please make sure there is correct sheet token!")
    }

    val sheets = data.getJSONArray(Config.SHEETS)
    if (null == sheets || sheets.size() < sheetNum) {
      throw new RuntimeException(s"The sheet $sheetNum is does not exists")
    }

    val sheetInfo = sheets.getJSONObject(sheetNum - 1)
    sheetInfo.getString(Config.SHEET_ID)
  }

  def getSheetData(sheetToken: String, range: String, sheetId: String): JSONArray = {
    var rangeNew = range
    // the range format is xxx!A1:C3
    if (!"".equals(rangeNew)) {
      rangeNew = "!" + rangeNew
    }
    val url = Config.SHEET_DATA_URL.format(sheetToken, sheetId, rangeNew)
    val data = this.requestFeishuApiAndGetData(url)
    if (null == data) {
      throw new RuntimeException("The data is empty, please make sure some data in sheet.")
    }

    val valueRange = data.getJSONObject(Config.VALUE_RANGE)
    if (null == valueRange) {
      throw new RuntimeException("The data is empty, please make sure some data in sheet.")
    }
    valueRange.getJSONArray(Config.VALUES)
  }

  def getDataset(
      sheetToken: String,
      range: String,
      titleLineNum: Int,
      ignoreTitleLine: Boolean,
      sheetNum: Int): (ArrayBuffer[Row], StructType) = {
    val sheetId = this.getSheetId(sheetToken, sheetNum)
    val values = getSheetData(sheetToken, range, sheetId)
    if (values.size() < titleLineNum) {
      throw new RuntimeException("The title line number is larger than data rows")
    }
    // start from 0
    var start = titleLineNum - 1

    if (ignoreTitleLine) {
      start += 1
    }

    var schema: StructType = null
    val schemaData = values.getJSONArray(titleLineNum - 1)
    val fields = ArrayBuffer[StructField]()
    for (index <- 0 until schemaData.size()) {
      val titleName = schemaData.getString(index)
      if (null == titleName) {
        throw new RuntimeException("The title name is not allowed null")
      }
      val field = DataTypes.createStructField(titleName, DataTypes.StringType, true)
      fields += field
    }
    schema = DataTypes.createStructType(fields.toArray)

    val rows = ArrayBuffer[Row]()
    for (index <- start until values.size()) {
      val jsonArr = values.getJSONArray(index)
      val arr = ArrayBuffer[String]()
      for (indexInner <- 0 until jsonArr.size()) {
        arr += jsonArr.getString(indexInner)
      }

      val row = Row.fromSeq(arr)
      rows += row
    }
    (rows, schema)
  }

  def requestFeishuApiAndGetData(url: String): JSONObject = {
    val result = this.requestFeishuApi(url, this.getToken)
    result.getJSONObject(Config.DATA)
  }

  def requestFeishuApi(url: String, token: String): JSONObject = {
    val httpGet = RequestBuilder.get()
      .setUri(url)
      .setHeader(HttpHeaders.AUTHORIZATION, s"Bearer $token")
      .build()

    var httpClient: CloseableHttpClient = null
    var resultStr: String = null
    try {
      httpClient = HttpClients.createDefault()
      val response: CloseableHttpResponse = httpClient.execute(httpGet)
      val statusCode = response.getStatusLine.getStatusCode
      if (statusCode != 200) {
        throw new RuntimeException(s"Request feishu api failed! statusCode is $statusCode")
      }
      val entity: HttpEntity = response.getEntity
      resultStr = EntityUtils.toString(entity)
    } catch {
      case e: Exception => throw e
    } finally {
      if (null != httpClient) {
        httpClient.close()
      }
    }

    val result = JSON.parseObject(resultStr)
    val code = result.getIntValue(Config.CODE)
    if (code != 0) {
      val errorMessage = result.getString(Config.MSG)
      throw new RuntimeException(
        s"Request feishu api error, the code is: $code and msg is: $errorMessage")
    }
    result
  }
}
