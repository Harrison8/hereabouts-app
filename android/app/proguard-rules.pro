# Keep rules for the signed Release build, which shrinks the code and the resources.
# The app uses kotlinx.serialization (@Serializable classes with generated serializers in AppData.kt and Care.kt, and
# the tree classes through Json.parseToJsonElement). That works under R8 because the library ships its own rules, so
# none are needed here. Add a rule here only when the shrunk app is seen to break.
