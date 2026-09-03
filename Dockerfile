# syntax=docker/dockerfile:1
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

ARG TRINO_VERSION=475

FROM alpine:3.20 AS web3-plugin

RUN apk add --no-cache unzip
COPY trino-web3-plugin/target/trino-web3-plugin-0.1-SNAPSHOT-plugin.zip /tmp/web3-plugin.zip
RUN mkdir -p /opt/web3 && unzip -q /tmp/web3-plugin.zip -d /opt/web3

FROM trinodb/trino:${TRINO_VERSION}

USER root
COPY --from=web3-plugin /opt/web3/ /usr/lib/trino/plugin/web3/
USER trino
