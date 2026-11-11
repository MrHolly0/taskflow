rootProject.name = "taskflow"

include("core-service:app")

include("core-service:shared:common")
include("core-service:shared:security")
include("core-service:shared:persistence")

include("core-service:modules:user:user-api")
include("core-service:modules:user:user-impl")
include("core-service:modules:task:task-api")
include("core-service:modules:task:task-impl")
include("core-service:modules:nlp-gateway:nlp-gateway-api")
include("core-service:modules:nlp-gateway:nlp-gateway-impl")
include("core-service:modules:notify:notify-api")
include("core-service:modules:notify:notify-impl")
include("core-service:modules:integration-telegram:integration-telegram-impl")
include("core-service:modules:audit:audit-api")
include("core-service:modules:audit:audit-impl")
include("core-service:modules:assistant:assistant-api")
include("core-service:modules:assistant:assistant-impl")

include("nlp-worker")
include("notification-worker")
include("core-service:shared:resource-control")

include("core-service:shared:telegram-widgets")
