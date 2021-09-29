{
    "jsonrpc": "2.0",
    "method": "problem.get",
    "params": {
        "output": "extend",
        "selectAcknowledges": "extend",
        "selectTags": "extend",
        "selectSuppressionData": "extend",
        "recent": ${recent},
        "sortfield": ["eventid"],
        <#if hostId??>
            "hostids":${hostId},
        </#if>
        <#if timeFrom??>
            "time_from":${timeFrom},
        </#if>
        <#if timeTill??>
            "time_till":${timeTill},
        </#if>
        "evaltype":2,
        "tags":[{"tag": "__alarm__"},{"tag": "__execute__"}],
        "filter":{
            "source":"0"
        },
        "sortorder": "DESC"
    },
    "auth": "${userAuth}",
    "id": 1
}