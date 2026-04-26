curl -i http://127.0.0.1:9180/apisix/admin/global_rules/1 \
  -H 'X-API-KEY: admin' \
  -X PUT -d '
{
  "plugins": {
    "limit-count": {
      "count": 1000,
      "time_window": 1,
      "rejected_code": 429,
      "rejected_msg": "Too many requests — quota exceeded",
      "key_type": "constant",
      "key": "global"
    }
  }
}'
