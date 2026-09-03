#!/usr/bin/env bash
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

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

"${repository_root}/docker/build.sh"
docker compose up --detach

cleanup()
{
    if [[ "${KEEP_CLUSTER:-0}" != "1" ]]; then
        docker compose down --remove-orphans
    fi
}
trap cleanup EXIT

until curl --fail --silent http://localhost:8080/v1/info >/dev/null; do
    sleep 2
done

worker_count="$(docker compose exec --env TERM=dumb -T coordinator trino \
    --server http://coordinator:8080 \
    --execute 'SELECT count(*) AS worker_count FROM system.runtime.nodes WHERE coordinator = false')"
worker_count="${worker_count//\"/}"
if [[ "${worker_count}" != "3" ]]; then
    printf 'Expected three registered workers, found %s\n' "${worker_count}" >&2
    exit 1
fi
printf 'Registered workers: %s\n' "${worker_count}"

schemas="$(docker compose exec --env TERM=dumb -T coordinator trino \
    --server http://coordinator:8080 \
    --catalog web3 \
    --execute 'SHOW SCHEMAS FROM web3')"
schemas="${schemas//\"/}"
if ! grep -Fxq "ethereum" <<<"${schemas}"; then
    printf 'Expected the ethereum schema in SHOW SCHEMAS output\n' >&2
    exit 1
fi
printf '%s\n' "${schemas}"

block_result="$(docker compose exec --env TERM=dumb -T coordinator trino \
    --server http://coordinator:8080 \
    --catalog web3 \
    --execute 'SELECT block_number, block_hash FROM web3.ethereum.blocks WHERE block_number BETWEEN 23000000 AND 23000000')"
block_result="${block_result//\"/}"
if ! grep -Fq "23000000" <<<"${block_result}"; then
    printf 'Expected block 23000000 in bounded Ethereum query output\n' >&2
    exit 1
fi
printf '%s\n' "${block_result}"
