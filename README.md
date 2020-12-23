# s3storagefrontend

<!---
[![start with why](https://img.shields.io/badge/start%20with-why%3F-brightgreen.svg?style=flat)](http://www.ted.com/talks/simon_sinek_how_great_leaders_inspire_action)
--->
[![GitHub release](https://img.shields.io/github/release/elbosso/s3storagefrontend/all.svg?maxAge=1)](https://GitHub.com/elbosso/s3storagefrontend/releases/)
[![GitHub tag](https://img.shields.io/github/tag/elbosso/s3storagefrontend.svg)](https://GitHub.com/elbosso/s3storagefrontend/tags/)
[![GitHub license](https://img.shields.io/github/license/elbosso/s3storagefrontend.svg)](https://github.com/elbosso/s3storagefrontend/blob/master/LICENSE)
[![GitHub issues](https://img.shields.io/github/issues/elbosso/s3storagefrontend.svg)](https://GitHub.com/elbosso/s3storagefrontend/issues/)
[![GitHub issues-closed](https://img.shields.io/github/issues-closed/elbosso/s3storagefrontend.svg)](https://GitHub.com/elbosso/s3storagefrontend/issues?q=is%3Aissue+is%3Aclosed)
[![contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat)](https://github.com/elbosso/s3storagefrontend/issues)
[![GitHub contributors](https://img.shields.io/github/contributors/elbosso/s3storagefrontend.svg)](https://GitHub.com/elbosso/s3storagefrontend/graphs/contributors/)
[![Github All Releases](https://img.shields.io/github/downloads/elbosso/s3storagefrontend/total.svg)](https://github.com/elbosso/s3storagefrontend)
[![Website elbosso.github.io](https://img.shields.io/website-up-down-green-red/https/elbosso.github.io.svg)](https://elbosso.github.io/)

## Overview

This project offers a frontend to an s3 storage - storing files only for a short time. 
You can build it by issuing

```
mvn compile package
```

and then starting the resulting monolithic jar file by issuing

```
$JAVA_HOME/bin/java -jar target/s3storagefrontend-<version>-jar-with-dependencies.jar
```

Alternatively one could just start the server using maven by  issuing

```
mvn compile exec:java
```

In both cases, the server starts on port 7000 - at the moment
only POST requests are supported. POSTs must be of
mimetype `multipart/form-data`, the form must contain a file
named _data_ containing the contents of the file being uploaded - it 
is then answered with a key needed to retrieve the file again.

At the moment, this is a prototype. It still lacks support for TLS.

However the recommended mode of using this is to use the provided _Dockerfile_ 
and _docker-compose.yml_ file. It is probably better 
to actually use a proxy solution like traefik (the docker-compose is 
already prepared for this) or similar
solutions so the services are actually accessible with a sound hostname and 
some default port.

## Configuration

All configuration is done vie _environment.env_ - just copy and rename _environment.env_template_ 
and customize its contents. Monitoring is configured by copying _src/main/resources/influxdb_micrometer.proprerties_template_
to _src/main/resources/influxdb_micrometer.proprerties_ and customizing it.

## Working with it

Just issue a HTTP POST request as multipart form data
(for example from a file upload from inside a web page):
```shell script
curl -F "data=@<some_file>" http://<host>:<port>/upload -o result.html 
``` 

A HTTP status code of 201 signifies success. If the operation is successful, the response
contains the direct link fpr the download of the uploaded object in three different locations:
The URL of the uploaded file (to be used to access the file later on or to share it with others)
is contained in the `head` of the HTML page as a `link` element,
in the body of the HTML page as a hyperlink (`a`) element and as a `Content-Location` header in the response.

If the HTTP header `Accepts` is sent with the request having a value of `text/plain`,
the service does only return the plain URL to the uploaded file for easier integration into scripts for example:</p>
```shell script
curl -H "Accept: text/plain" -F "data=@<some_file_name>" http://<host>:<port>/upload -o result.html 
```

## Expiration of documents

At the moment the bucket given in _environment.env_ must exist. However it is not necessary to
configure any expiration times upon it - after starting the service, the bucket is configured accordingly.