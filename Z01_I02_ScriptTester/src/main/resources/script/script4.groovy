import com.sap.gateway.ip.core.customdev.util.Message;

def Message processData(Message message) {
    def consoleLog = message.getProperty("consoleLog") as String
    def runnerDelta = message.getProperty("runnerDelta")
    message.setProperty("consoleLog", null)
    message.setProperty("runnerDelta", null)
    def mapProperties = message.getProperties();
    def body = message.getBody(java.lang.String) as String;
    String Pbody = ""
    String Hbody = ""
    mapProperties.each { it -> Pbody += "$it.key = ${shown(it.key, it.value)}; \n" }
    def ex = mapProperties.get("CamelExceptionCaught");
    def mapHeaders = message.getHeaders();
    mapHeaders.each { it -> Hbody += "$it.key = ${shown(it.key, it.value)}; \n" }
    def builderJSON = new groovy.json.JsonBuilder()
    builderJSON{
        properties(Pbody.bytes.encodeBase64().toString())
        headers(Hbody.bytes.encodeBase64().toString())
        payload(body.bytes.encodeBase64().toString())
        error(ex.message.bytes.encodeBase64().toString())
        // console and changes up to the failure (set by script1 before the exception left it)
        console((consoleLog ?: '').bytes.encodeBase64().toString())
        delta(runnerDelta)
    }
    message.setHeader("CamelHttpResponseCode", "400");
    message.setBody(builderJSON.toPrettyString());
    return message;
}

// Credentials the runtime keeps in headers and properties are never returned
def shown(key, value) {
    key ==~ /(?i).*(auth|passport|password|secret|token|cookie|credential).*/ ? '<hidden>' : value
}
