import com.sap.gateway.ip.core.customdev.util.Message

Message processData(Message message) {

    String inputHdrs = message.getProperty("inputHdrs")
    if(inputHdrs ){
        byte[] decodedHdrs = inputHdrs.decodeBase64()
        inputHdrs = new String(decodedHdrs)
        inputHdrs.split(';').each{ 
            def param_val_pair = it.split('=')
            message.setHeader(param_val_pair[0],param_val_pair[1])
        }
        message.setProperty("inputHdrs","")
    }

    String inputParams = message.getProperty("inputParams")
    if(inputParams){
        byte[] decodedParams = inputParams.decodeBase64()
        inputParams = new String(decodedParams)
        inputParams.split(';').each{ 
            def param_val_pair = it.split('=')
            message.setProperty(param_val_pair[0],param_val_pair[1])
        }
        message.setProperty("inputParams","")
    }

    return message
}