# Graph Report - /Users/holly/IdeaProjects/taskflow  (2026-04-24)

## Corpus Check
- 89 files · ~7,861 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 289 nodes · 380 edges · 24 communities detected
- Extraction: 72% EXTRACTED · 28% INFERRED · 0% AMBIGUOUS · INFERRED: 108 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Task Management|Task Management]]
- [[_COMMUNITY_Testing|Testing]]
- [[_COMMUNITY_Telegram Integration|Telegram Integration]]
- [[_COMMUNITY_Testing|Testing]]
- [[_COMMUNITY_Module Cluster (BotCommandRouter, .handle(), .parseComma...)|Module Cluster (BotCommandRouter, .handle(), .parseComma...)]]
- [[_COMMUNITY_Telegram Integration|Telegram Integration]]
- [[_COMMUNITY_Testing|Testing]]
- [[_COMMUNITY_Authentication|Authentication]]
- [[_COMMUNITY_User Management|User Management]]
- [[_COMMUNITY_Module Cluster (TextMessageHandler.java, .create(), Text...)|Module Cluster (TextMessageHandler.java, .create(), Text...)]]
- [[_COMMUNITY_Task Management|Task Management]]
- [[_COMMUNITY_Authentication|Authentication]]
- [[_COMMUNITY_Module Cluster (AccessDeniedException, .AccessDeniedExce...)|Module Cluster (AccessDeniedException, .AccessDeniedExce...)]]
- [[_COMMUNITY_Task Management|Task Management]]
- [[_COMMUNITY_Telegram Integration|Telegram Integration]]
- [[_COMMUNITY_User Management|User Management]]
- [[_COMMUNITY_Telegram Integration|Telegram Integration]]
- [[_COMMUNITY_Task Management|Task Management]]
- [[_COMMUNITY_Task Management|Task Management]]
- [[_COMMUNITY_Module Cluster (GroupJpaEntity.java, GroupJpaEntity, .pr...)|Module Cluster (GroupJpaEntity.java, GroupJpaEntity, .pr...)]]
- [[_COMMUNITY_User Management|User Management]]
- [[_COMMUNITY_Module Cluster (RecurrenceJpaEntity.java, RecurrenceJpaE...)|Module Cluster (RecurrenceJpaEntity.java, RecurrenceJpaE...)]]
- [[_COMMUNITY_Module Cluster (TagJpaEntity.java, TagJpaEntity...)|Module Cluster (TagJpaEntity.java, TagJpaEntity...)]]
- [[_COMMUNITY_Module Cluster (ReminderJpaEntity.java, ReminderJpaEntit...)|Module Cluster (ReminderJpaEntity.java, ReminderJpaEntit...)]]

## God Nodes (most connected - your core abstractions)
1. `TelegramInitDataValidatorTest` - 10 edges
2. `TaskServiceTest` - 10 edges
3. `TaskServiceImpl` - 9 edges
4. `AuthController` - 8 edges
5. `UpdateRouterTest` - 8 edges
6. `JwtServiceTest` - 7 edges
7. `TelegramLoginWidgetValidatorTest` - 7 edges
8. `TaskService` - 7 edges
9. `TaskController` - 7 edges
10. `SecurityConfig` - 6 edges

## Surprising Connections (you probably didn't know these)
- `TaskServiceImpl` --implements--> `TaskService`  [EXTRACTED]
  /Users/holly/IdeaProjects/taskflow/core-service/modules/task/task-impl/src/main/java/ru/taskflow/task/application/TaskServiceImpl.java →   _Bridges community 0 → community 1_

## Communities

### Community 0 - "Task Management"
Cohesion: 0.11
Nodes (5): GroupRepository, TagRepository, TaskMapper, TaskServiceImpl, TaskServiceTest

### Community 1 - "Testing"
Cohesion: 0.1
Nodes (6): CallbackHandler, CallbackHandlerTest, TaskController, TaskService, TelegramApiClient, TelegramMessageSender

### Community 2 - "Telegram Integration"
Cohesion: 0.15
Nodes (3): TelegramInitDataValidator, TelegramInitDataValidatorTest, TelegramLoginWidgetValidatorTest

### Community 3 - "Testing"
Cohesion: 0.11
Nodes (7): GlobalExceptionHandler, GroupNotFoundException, NotFoundException, TaskNotFoundException, UserRepository, UserService, UserServiceImpl

### Community 4 - "Module Cluster (BotCommandRouter, .handle(), .parseComma...)"
Cohesion: 0.18
Nodes (4): BotCommandRouter, BotCommandRouterTest, VoiceMessageHandler, VoiceMessageHandlerTest

### Community 5 - "Telegram Integration"
Cohesion: 0.13
Nodes (4): TelegramWebhookController, TelegramWebhookControllerTest, UpdateIdempotencyService, UpdateIdempotencyServiceTest

### Community 6 - "Testing"
Cohesion: 0.18
Nodes (4): JwtAuthenticationFilter, JwtService, JwtServiceTest, OncePerRequestFilter

### Community 7 - "Authentication"
Cohesion: 0.27
Nodes (2): AuthController, UserService

### Community 8 - "User Management"
Cohesion: 0.32
Nodes (2): UpdateRouter, UpdateRouterTest

### Community 9 - "Module Cluster (TextMessageHandler.java, .create(), Text...)"
Cohesion: 0.36
Nodes (2): TextMessageHandler, TextMessageHandlerTest

### Community 10 - "Task Management"
Cohesion: 0.25
Nodes (1): TaskService

### Community 11 - "Authentication"
Cohesion: 0.29
Nodes (1): SecurityConfig

### Community 12 - "Module Cluster (AccessDeniedException, .AccessDeniedExce...)"
Cohesion: 0.29
Nodes (3): AccessDeniedException, NotFoundException, RuntimeException

### Community 13 - "Task Management"
Cohesion: 0.4
Nodes (1): TaskRepository

### Community 14 - "Telegram Integration"
Cohesion: 0.5
Nodes (1): TelegramLoginWidgetValidator

### Community 15 - "User Management"
Cohesion: 0.5
Nodes (1): UserJpaEntity

### Community 16 - "Telegram Integration"
Cohesion: 0.5
Nodes (1): TelegramBotConfig

### Community 17 - "Task Management"
Cohesion: 0.5
Nodes (1): TaskJpaEntity

### Community 18 - "Task Management"
Cohesion: 0.67
Nodes (1): TaskFlowApplication

### Community 19 - "Module Cluster (GroupJpaEntity.java, GroupJpaEntity, .pr...)"
Cohesion: 0.67
Nodes (1): GroupJpaEntity

### Community 20 - "User Management"
Cohesion: 1.0
Nodes (1): UserSettingsJpaEntity

### Community 22 - "Module Cluster (RecurrenceJpaEntity.java, RecurrenceJpaE...)"
Cohesion: 1.0
Nodes (1): RecurrenceJpaEntity

### Community 23 - "Module Cluster (TagJpaEntity.java, TagJpaEntity...)"
Cohesion: 1.0
Nodes (1): TagJpaEntity

### Community 24 - "Module Cluster (ReminderJpaEntity.java, ReminderJpaEntit...)"
Cohesion: 1.0
Nodes (1): ReminderJpaEntity

## Knowledge Gaps
- **4 isolated node(s):** `UserSettingsJpaEntity`, `RecurrenceJpaEntity`, `TagJpaEntity`, `ReminderJpaEntity`
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Authentication`** (12 nodes): `AuthController`, `.extractJsonLong()`, `.extractJsonString()`, `.issueTokens()`, `.loginWidgetAuth()`, `.miniAppAuth()`, `.parseMiniAppUser()`, `.refresh()`, `UserService.java`, `UserService`, `.findById()`, `.findOrCreateByTelegram()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `User Management`** (12 nodes): `UpdateRouter.java`, `UpdateRouter`, `.resolveUser()`, `.route()`, `UpdateRouterTest`, `.callbackQuery_callsCallbackHandler()`, `.command_callsCommandRouter()`, `.stubUser()`, `.textMessage()`, `.textMessage_callsTextHandler()`, `.voiceMessage()`, `.voiceMessage_callsVoiceHandler()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Module Cluster (TextMessageHandler.java, .create(), Text...)`** (9 nodes): `TextMessageHandler.java`, `.create()`, `TextMessageHandler`, `.handle()`, `TextMessageHandlerTest`, `.message()`, `.plainText_createsTask()`, `.plainText_sendsConfirmation()`, `.taskResponse()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Task Management`** (8 nodes): `TaskService.java`, `TaskService`, `.complete()`, `.create()`, `.delete()`, `.findAll()`, `.findById()`, `.update()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Authentication`** (7 nodes): `SecurityConfig.java`, `SecurityConfig`, `.jwtAuthenticationFilter()`, `.jwtService()`, `.securityFilterChain()`, `.telegramInitDataValidator()`, `.telegramLoginWidgetValidator()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Task Management`** (5 nodes): `TaskRepository.java`, `TaskRepository`, `.findAllWithFilter()`, `.findByIdAndUserId()`, `.findAll()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Telegram Integration`** (4 nodes): `TelegramLoginWidgetValidator.java`, `TelegramLoginWidgetValidator`, `.TelegramLoginWidgetValidator()`, `.validate()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `User Management`** (4 nodes): `UserJpaEntity.java`, `UserJpaEntity`, `.prePersist()`, `.preUpdate()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Telegram Integration`** (4 nodes): `TelegramBotConfig.java`, `TelegramBotConfig`, `.telegramApiClient()`, `.voiceMessageHandler()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Task Management`** (4 nodes): `TaskJpaEntity.java`, `TaskJpaEntity`, `.prePersist()`, `.preUpdate()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Task Management`** (3 nodes): `TaskFlowApplication.java`, `TaskFlowApplication`, `.main()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Module Cluster (GroupJpaEntity.java, GroupJpaEntity, .pr...)`** (3 nodes): `GroupJpaEntity.java`, `GroupJpaEntity`, `.prePersist()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `User Management`** (2 nodes): `UserSettingsJpaEntity.java`, `UserSettingsJpaEntity`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Module Cluster (RecurrenceJpaEntity.java, RecurrenceJpaE...)`** (2 nodes): `RecurrenceJpaEntity.java`, `RecurrenceJpaEntity`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Module Cluster (TagJpaEntity.java, TagJpaEntity...)`** (2 nodes): `TagJpaEntity.java`, `TagJpaEntity`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Module Cluster (ReminderJpaEntity.java, ReminderJpaEntit...)`** (2 nodes): `ReminderJpaEntity.java`, `ReminderJpaEntity`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `AuthController` connect `Authentication` to `Testing`?**
  _High betweenness centrality (0.069) - this node is a cross-community bridge._
- **Why does `TaskServiceImpl` connect `Task Management` to `Testing`, `Task Management`?**
  _High betweenness centrality (0.042) - this node is a cross-community bridge._
- **Why does `TaskController` connect `Testing` to `Task Management`, `Module Cluster (TextMessageHandler.java, .create(), Text...)`?**
  _High betweenness centrality (0.035) - this node is a cross-community bridge._
- **What connects `UserSettingsJpaEntity`, `RecurrenceJpaEntity`, `TagJpaEntity` to the rest of the system?**
  _4 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Task Management` be split into smaller, more focused modules?**
  _Cohesion score 0.11 - nodes in this community are weakly interconnected._
- **Should `Testing` be split into smaller, more focused modules?**
  _Cohesion score 0.1 - nodes in this community are weakly interconnected._
- **Should `Testing` be split into smaller, more focused modules?**
  _Cohesion score 0.11 - nodes in this community are weakly interconnected._