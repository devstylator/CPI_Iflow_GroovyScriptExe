import com.sap.gateway.ip.core.customdev.util.Message

Message processData(Message message) {
    def consoleLog = message.getProperties().get("consoleLog") as String
    message.setProperty("consoleLog", null)
    // What the script changed (from script1), returned as plain JSON next to the Base64 fields
    def runnerDelta = message.getProperty("runnerDelta")
    message.setProperty("runnerDelta", null)
    // The script failed (caught in script1): same error field and status as the exception subprocess
    def runnerError = message.getProperty("runnerError") as String
    message.setProperty("runnerError", null)
    def mapProperties = message.getProperties();
    def mapHeaders = message.getHeaders();
    def body = message.getBody(java.lang.String) as String;
    String Pbody = ""
    String Hbody = ""
    mapProperties.each { it -> Pbody += "$it.key = ${shown(it.key, it.value)};" }
    mapHeaders.each { it -> Hbody += "$it.key = ${shown(it.key, it.value)};" }
    def builderJSON = new groovy.json.JsonBuilder()
    builderJSON{
        properties(Pbody.bytes.encodeBase64().toString())
        headers(Hbody.bytes.encodeBase64().toString())
        payload(body.bytes.encodeBase64().toString())
        console(consoleLog.bytes.encodeBase64().toString())
        delta(runnerDelta)
        if (runnerError) error(runnerError.bytes.encodeBase64().toString())
    }
    if (runnerError) message.setHeader("CamelHttpResponseCode", "400")
    message.setBody(builderJSON.toPrettyString())
    return message
}

// Credentials the runtime keeps in headers and properties are never returned
def shown(key, value) {
    key ==~ /(?i).*(auth|passport|password|secret|token|cookie|credential).*/ ? '<hidden>' : value
}
