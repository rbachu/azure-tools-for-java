/**
 * Copyright (c) Microsoft Corporation
 * <p>
 * <p>
 * All rights reserved.
 * <p>
 * <p>
 * MIT License
 * <p>
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 * <p>
 * <p>
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.microsoft.azure.hdinsight.spark.common;

import com.microsoft.azure.hdinsight.common.logger.ILogger;
import com.microsoft.azure.hdinsight.sdk.cluster.ClusterDetail;
import com.microsoft.azure.hdinsight.sdk.cluster.IClusterDetail;
import com.microsoft.azure.hdinsight.sdk.common.HDIException;
import com.microsoft.azure.hdinsight.sdk.common.HttpObservable;
import com.microsoft.azure.hdinsight.sdk.storage.StorageAccountTypeEnum;
import com.microsoft.azure.hdinsight.sdk.storage.webhdfs.WebHdfsParamsBuilder;
import com.microsoft.azure.hdinsight.spark.jobs.JobUtils;
import com.microsoft.azure.sqlbigdata.sdk.cluster.SqlBigDataLivyLinkClusterDetail;
import com.microsoft.azuretools.azurecommons.helpers.NotNull;
import com.microsoft.azuretools.azurecommons.helpers.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import rx.Observable;
import rx.exceptions.Exceptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownServiceException;
import java.util.List;

public class WebHDFSDeploy implements Deployable, ILogger {
    @NotNull
    IClusterDetail cluster;

    @Nullable
    private HttpObservable http;

    @NotNull
    private List<NameValuePair> createDirReqParams;

    @NotNull
    private List<NameValuePair> uploadReqParams;

    public WebHDFSDeploy(IClusterDetail cluster, HttpObservable http) {
        this.cluster = cluster;
        this.uploadReqParams = new WebHdfsParamsBuilder("CREATE")
                .setOverwrite("true")
                .setPermission("777")
                .build();

        this.createDirReqParams = new WebHdfsParamsBuilder("MKDIRS")
                .setPermission("777")
                .build();

        this.http = http;
        if (http != null) {
            http.setDefaultRequestConfig(RequestConfig.custom().setExpectContinueEnabled(true).build());
        } else {
            throw new IllegalArgumentException("Cluster type doesen't support webHDFS storage type.");
        }
    }


    @Override
    public URI getUploadDir(@NotNull String rootPath) {
        return URI.create(rootPath)
                .resolve(JobUtils.getFormatPathByDate() + "/");
    }

    @NotNull
    public Observable<String> deploy(@NotNull File src, @NotNull URI dest) {
        //three steps to upload via webhdfs
        // 1.put request to create new dir
        // 2.put request to get 307 redirect uri from response
        // 3.put redirect request with file content as setEntity

        HttpPut req = new HttpPut(dest.toString());
        return http.request(req, null, this.createDirReqParams, null)
                .doOnNext(
                        resp -> {
                            if (resp.getStatusLine().getStatusCode() != 200) {
                                Exceptions.propagate(new UnknownServiceException("Can not create directory to save artifact using webHDFS storage type"));
                            }
                        }
                )
                .map(ignored -> new HttpPut(dest.resolve(src.getName()).toString()))
                .flatMap(put -> http.request(put, null, this.uploadReqParams, null))
                .map(resp -> resp.getFirstHeader("Location").getValue())
                .doOnNext(redirectedUri -> {
                    if (StringUtils.isBlank(redirectedUri)) {
                        Exceptions.propagate(new UnknownServiceException("Can not get valid redirect uri using webHDFS storage type"));
                    }
                })
                .map(redirectedUri -> new HttpPut(redirectedUri))
                .flatMap(put -> {
                    try {
                        InputStreamEntity reqEntity = new InputStreamEntity(
                                new FileInputStream(src),
                                -1,
                                ContentType.APPLICATION_OCTET_STREAM);
                        reqEntity.setChunked(true);

                        return http.request(put, new BufferedHttpEntity(reqEntity), URLEncodedUtils.parse(put.getURI(), "UTF-8"), null);
                    } catch (IOException ex) {
                        throw new RuntimeException(new IllegalArgumentException("Can not get local artifact when uploading" + ex.toString()));
                    }
                })
                .map(ignored -> {
                    try {
                        return getArtifactUploadedPath(dest.resolve(src.getName()).toString());
                    } catch (URISyntaxException ex) {
                        throw new RuntimeException(new IllegalArgumentException("Can not get valid artifact upload path" + ex.toString()));
                    }
                });
    }

    @Override
    @Nullable
    public String getArtifactUploadedPath(String rootPath) throws URISyntaxException {
        List<NameValuePair> params = new WebHdfsParamsBuilder("OPEN").build();
        URIBuilder uriBuilder = new URIBuilder(rootPath);
        uriBuilder.addParameters(params);
        return uriBuilder.build().toString();
    }
}

