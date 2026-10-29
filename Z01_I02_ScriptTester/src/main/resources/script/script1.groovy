import com.sap.gateway.ip.core.customdev.util.Message
import groovy.transform.Field
import java.io.StringWriter
import java.io.PrintWriter

// Keys the runner itself uses; they are not reported as changes made by the script.
// @Field because CPI calls processData without running the script body.
@Field static final Set RUNNER_KEYS = ['inputScript', 'inputPayload', 'inputHdrs', 'inputParams', 'consoleLog', 'runnerFunction', 'runnerDelta', 'runnerError',
                                     'SAP_MessageProcessingLog'] as Set
@Field static final java.util.regex.Pattern SENSITIVE = ~/(?i)auth|passport|password|secret|token|cookie|credential/

Message processData(Message message) {
    // 1. Decode the dynamic script
    String inputPayload = message.getProperty("inputScript")
    if (inputPayload == null) {
        throw new Exception("Property 'inputScript' is missing")
    }
    byte[] decodedPayload = inputPayload.decodeBase64()
    String scriptCode = new String(decodedPayload, "UTF-8")

    // Function to call: processData unless the caller passed runnerFunction=<name> in inputParams
    String function = message.getProperty("runnerFunction") ?: "processData"
    message.setProperty("runnerFunction", null)

    // 2. Setup output capture
    StringWriter logWriter = new StringWriter()
    PrintWriter printWriter = new PrintWriter(logWriter)

    // 3. Create Binding: 'out' for println support, messageLogFactory as in a real script step
    Binding binding = new Binding()
    binding.setVariable("out", printWriter)
    binding.setVariable("messageLogFactory", messageLogFactory)

    // Snapshot to report what the script changed
    Map headersBefore = snapshot(message.getHeaders())
    Map propertiesBefore = snapshot(message.getProperties())

    // 4. Initialize shell and execute. A failure of the script is caught here, not thrown: the exception
    //    subprocess would only see the message as it was before this step, without console and changes.
    //    The wrapper (script3) turns runnerError into the HTTP 400 error response.
    try {
        GroovyShell shell = new GroovyShell(binding)
        Script script = shell.parse(scriptCode)
        message = script.invokeMethod(function, message)
    } catch (Throwable e) {
        def line = e.stackTrace.find { it.fileName ==~ /Script\d+\.groovy/ }?.lineNumber
        message.setProperty("runnerError", line ? "${e} @ line ${line}".toString() : e.toString())
    } finally {
        printWriter.flush()
        message.setProperty("consoleLog", logWriter.toString())
        message.setProperty("runnerDelta", [
            headers   : delta(headersBefore, snapshot(message.getHeaders())),
            properties: delta(propertiesBefore, snapshot(message.getProperties()))
        ])
    }
    return message
}

Map snapshot(Map values) {
    values.findAll { k, v -> !(k in RUNNER_KEYS) }.collectEntries { k, v -> [(k): v == null ? null : v.toString()] }
}

Map delta(Map before, Map after) {
    def hide = { k, v -> (k =~ SENSITIVE).find() ? '<hidden>' : v }
    [
        set    : after.findAll { k, v -> !before.containsKey(k) || before[k] != v }.collectEntries { k, v -> [(k): hide(k, v)] },
        removed: before.keySet().findAll { !after.containsKey(it) }.sort()
    ]
}
