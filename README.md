# CPI Groovy Script Runner

An integration flow for SAP Cloud Integration (CPI) that **runs a Groovy script on your tenant without deploying
it**. You post the script, a test body, headers and properties; the runner executes the script inside a real script
step and returns the resulting body, headers, properties, everything the script printed, and exactly what it
changed. Errors come back with the line number.

Both script runtimes are supported:

| Script step | Groovy | Message API | Select with |
|---|---|---|---|
| 1.x (default) | 2.4.21 | `com.sap.gateway.ip.core.customdev.util.Message` | no header, or `groovy-version: 1` |
| 2.x | 4.0.29 | `com.sap.it.script.v2.api.Message` | header `groovy-version: 2` |

A script sent to the wrong runtime fails to compile, just as it would in the real step, which makes the runner a quick
check before upgrading a script to 2.x.

It is the backend of the Groovy IDE in [btp_ci_toolkit](https://github.com/devstylator/btp_ci_toolkit)
([docs](https://github.com/devstylator/btp_ci_toolkit/blob/main/docs/groovy-ide.md)).

> [!WARNING]
> The runner executes **any** code it receives, with the permissions of the Cloud Integration runtime. There is no
> sandbox. Deploy it only on development or trial tenants, never on test or production.

## Install

1. Zip the contents of the [`Z01_I02_ScriptTester`](Z01_I02_ScriptTester) folder, with `META-INF/` and `src/` at
   the top level of the zip:
   ```sh
   cd Z01_I02_ScriptTester && zip -r ../Z01_I02_ScriptTester.zip . && cd ..
   ```
2. In Cloud Integration: *Design* → your package → *Add* → *Integration Flow* → *Upload*, and select the zip.
3. Deploy it. The endpoint is `https://<runtime host>/http/v2/test/script`.
4. Create a service key for the runtime with plan **`integration-flow`** (*Process Integration Runtime* service,
   grant type client credentials, role `ESBMessaging.send`). Its `url` is the runtime host; `clientid`,
   `clientsecret` and `tokenurl` give you an OAuth token.

## Use

`POST /http/v2/test/script` with a JSON body. Every field is **Base64**-encoded:

| Field | Content |
|---|---|
| `inputScript` | The Groovy script (UTF-8). It must define `Message processData(Message message)`, or the function named in `runnerFunction` |
| `inputPayload` | The message body the script receives. Empty or omitted: empty body |
| `inputHdrs` | Message headers as `Name=Value;Name=Value` |
| `inputParams` | Exchange properties as `Name=Value;Name=Value`. The reserved property `runnerFunction=<name>` calls that function instead of `processData` |

Example with `curl` (Linux; on macOS use `base64` without `-w0`):

```sh
cat > hello.groovy <<'EOF'
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

Message processData(Message message) {
    def order = new JsonSlurper().parse(message.getBody(java.io.Reader))
    println "items: ${order.items.size()}"
    message.setHeader('X-Order-Id', order.id)
    message.setProperty('itemCount', order.items.size())
    message.setBody(JsonOutput.toJson([id: order.id, total: order.items.sum { it.qty * it.price }]))
    return message
}
EOF

# values from the integration-flow service key
TOKEN=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials "$TOKEN_URL" | jq -r .access_token)

jq -n --arg s "$(base64 -w0 hello.groovy)" \
      --arg p "$(printf '%s' '{"id":"A-1","items":[{"qty":2,"price":5},{"qty":1,"price":3}]}' | base64 -w0)" \
      --arg h "$(printf 'Source=demo' | base64 -w0)" \
      '{inputScript: $s, inputPayload: $p, inputHdrs: $h, inputParams: ""}' |
curl -s -X POST "$RUNTIME_URL/http/v2/test/script" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d @- > result.json

jq -r '.payload | @base64d' result.json   # {"id":"A-1","total":13}
jq -r '.console | @base64d' result.json   # items: 2
jq '.delta' result.json
```

`delta` lists exactly what the script changed:

```json
{
  "headers":    { "set": { "X-Order-Id": "A-1" }, "removed": [] },
  "properties": { "set": { "itemCount": "2" },    "removed": [] }
}
```

For a Groovy 4 script (script step 2.x), add `-H "groovy-version: 2"` and import
`com.sap.it.script.v2.api.Message`.

### Response

| Field | Content |
|---|---|
| `payload` | Base64 of the body after the script |
| `headers`, `properties` | Base64 of all headers / exchange properties after the script, as `name = value;` text (including the runtime's own `Camel*`/`SAP_*` entries) |
| `console` | Base64 of everything the script printed with `println`/`print` |
| `delta` | Plain JSON (not Base64): what the script set or removed, see above |
| `error` | Base64 of the error, only when the script failed: `<exception>: <message> @ line <n>`, or the compiler message |

| HTTP status | Meaning |
|---|---|
| 200 | The script ran without error |
| 400 | The script failed (all fields present, plus `error`), or the request itself was invalid (for example not JSON) |
| 401 / 403 | Missing token / the client lacks the role `ESBMessaging.send` |
| 404 | Wrong path, or the runner is not deployed |

The full API is described in [openapi.yaml](openapi.yaml) (OpenAPI 3.0).

## What you should know

- **The script runs inside the runner,** after the runner's own steps. The full header and property lists therefore
  include the runner's entries; use `delta` to see what your script did.
- **Credential-like values** (names containing auth, passport, password, secret, token, cookie or credential) are
  returned as `<hidden>`.
- **`;` and `=`** cannot be used in header or property names and values sent in `inputHdrs`/`inputParams`. Set such
  values inside the script instead.
- **One script file per call.** Other script files of an iFlow and script collections cannot be imported; inline the
  code you need for the test.
- **`messageLogFactory`** is available as in a real script step.
- **Limits:** request body up to 40 MB; about 60 s per call. A small script takes about 2 s round trip.
- **Responses carry no `Content-Type` header.** Parse the body as JSON anyway.

## How it works

1. A *JSON to XML Converter* and a *Content Modifier* turn the request fields into exchange properties.
2. *Base64 Decoder* and a script turn `inputPayload` into the body and `inputHdrs`/`inputParams` into headers and
   properties.
3. A *Router* checks the `groovy-version` header: `2` goes to *Execute script v2* (script step 2.0.0, Groovy 4),
   anything else to *Execute script* (script step 1.1.2, Groovy 2.4).
4. The executor parses the posted script with `GroovyShell`, captures `println`, calls the function, and records what
   changed. A failing script is caught here, so its console output and changes are still returned.
5. A wrapper script builds the JSON response (HTTP 400 when the script failed). An exception subprocess answers when
   the runner itself fails.
