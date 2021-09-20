{
    "jsonrpc": "2.0",
    "method": "usermacro.update",
    "params": {
        "hostmacroid": "${macroid}",
        "value": "${value}",
    <#if description??>
        "description":"${description}"
    </#if>
    },
    "auth": "${userAuth}",
    "id": 1
}