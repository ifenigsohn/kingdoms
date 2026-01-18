# Diff Details

Date : 2026-01-16 18:29:26

Directory c:\\Users\\isaac\\OneDrive\\Documents\\kingdoms-test-NEW (MAIN)\\src

Total : 35 files,  5339 codes, 299 comments, 1195 blanks, all 6833 lines

[Summary](results.md) / [Details](details.md) / [Diff Summary](diff.md) / Diff Details

## Files
| filename | language | code | comment | blank | total |
| :--- | :--- | ---: | ---: | ---: | ---: |
| [.github/workflows/build.yml](/.github/workflows/build.yml) | YAML | -24 | -4 | -2 | -30 |
| [README.md](/README.md) | Markdown | -3 | 0 | -2 | -5 |
| [build.gradle](/build.gradle) | Gradle | -56 | -14 | -18 | -88 |
| [gradle.properties](/gradle.properties) | Java Properties | -11 | -6 | -5 | -22 |
| [gradle/wrapper/gradle-wrapper.properties](/gradle/wrapper/gradle-wrapper.properties) | Java Properties | -7 | 0 | -1 | -8 |
| [gradlew.bat](/gradlew.bat) | Batch | -40 | -32 | -22 | -94 |
| [logs/latest.log](/logs/latest.log) | Log | 0 | 0 | -1 | -1 |
| [settings.gradle](/settings.gradle) | Gradle | -10 | 0 | 0 | -10 |
| [src/client/java/name/kingdoms/client/clientHoverCardCache.java](/src/client/java/name/kingdoms/client/clientHoverCardCache.java) | Java | 10 | 0 | 3 | 13 |
| [src/client/java/name/kingdoms/client/kingdomBordersMapScreen.java](/src/client/java/name/kingdoms/client/kingdomBordersMapScreen.java) | Java | 13 | 2 | 8 | 23 |
| [src/client/java/name/kingdoms/client/mailScreen.java](/src/client/java/name/kingdoms/client/mailScreen.java) | Java | 199 | 22 | 57 | 278 |
| [src/main/java/name/kingdoms/Kingdoms.java](/src/main/java/name/kingdoms/Kingdoms.java) | Java | 4 | 0 | 0 | 4 |
| [src/main/java/name/kingdoms/KingdomsCommands.java](/src/main/java/name/kingdoms/KingdomsCommands.java) | Java | 1,749 | 94 | 398 | 2,241 |
| [src/main/java/name/kingdoms/aiKingdomState.java](/src/main/java/name/kingdoms/aiKingdomState.java) | Java | 80 | 10 | 27 | 117 |
| [src/main/java/name/kingdoms/blueprint/BlueprintPlacerEngine.java](/src/main/java/name/kingdoms/blueprint/BlueprintPlacerEngine.java) | Java | 192 | -31 | 54 | 215 |
| [src/main/java/name/kingdoms/blueprint/CastleOriginState.java](/src/main/java/name/kingdoms/blueprint/CastleOriginState.java) | Java | 0 | -1 | 1 | 0 |
| [src/main/java/name/kingdoms/blueprint/KingdomSatelliteSpawner.java](/src/main/java/name/kingdoms/blueprint/KingdomSatelliteSpawner.java) | Java | 102 | 27 | 15 | 144 |
| [src/main/java/name/kingdoms/blueprint/worldGenBluePrintAutoSpawner.java](/src/main/java/name/kingdoms/blueprint/worldGenBluePrintAutoSpawner.java) | Java | 83 | 23 | 30 | 136 |
| [src/main/java/name/kingdoms/diplomacy/AiDiplomacyEvent.java](/src/main/java/name/kingdoms/diplomacy/AiDiplomacyEvent.java) | Java | 12 | 1 | 2 | 15 |
| [src/main/java/name/kingdoms/diplomacy/AiDiplomacyEventState.java](/src/main/java/name/kingdoms/diplomacy/AiDiplomacyEventState.java) | Java | 14 | 1 | 8 | 23 |
| [src/main/java/name/kingdoms/diplomacy/AiDiplomacyTicker.java](/src/main/java/name/kingdoms/diplomacy/AiDiplomacyTicker.java) | Java | 586 | 44 | 152 | 782 |
| [src/main/java/name/kingdoms/diplomacy/AiLetterText.java](/src/main/java/name/kingdoms/diplomacy/AiLetterText.java) | Java | 1,584 | 43 | 244 | 1,871 |
| [src/main/java/name/kingdoms/diplomacy/AiRelationNormalizer.java](/src/main/java/name/kingdoms/diplomacy/AiRelationNormalizer.java) | Java | 41 | 5 | 15 | 61 |
| [src/main/java/name/kingdoms/diplomacy/AiRelationsState.java](/src/main/java/name/kingdoms/diplomacy/AiRelationsState.java) | Java | 15 | 0 | 5 | 20 |
| [src/main/java/name/kingdoms/diplomacy/AllianceState.java](/src/main/java/name/kingdoms/diplomacy/AllianceState.java) | Java | 20 | 2 | 3 | 25 |
| [src/main/java/name/kingdoms/diplomacy/DiplomacyEvaluator.java](/src/main/java/name/kingdoms/diplomacy/DiplomacyEvaluator.java) | Java | 60 | 20 | 26 | 106 |
| [src/main/java/name/kingdoms/diplomacy/DiplomacyMailGenerator.java](/src/main/java/name/kingdoms/diplomacy/DiplomacyMailGenerator.java) | Java | 145 | 23 | 52 | 220 |
| [src/main/java/name/kingdoms/diplomacy/DiplomacyMailboxState.java](/src/main/java/name/kingdoms/diplomacy/DiplomacyMailboxState.java) | Java | 42 | -2 | 8 | 48 |
| [src/main/java/name/kingdoms/diplomacy/DiplomacyRelationsState.java](/src/main/java/name/kingdoms/diplomacy/DiplomacyRelationsState.java) | Java | 4 | 1 | 2 | 7 |
| [src/main/java/name/kingdoms/diplomacy/DiplomacyResponseQueue.java](/src/main/java/name/kingdoms/diplomacy/DiplomacyResponseQueue.java) | Java | 22 | 11 | 11 | 44 |
| [src/main/java/name/kingdoms/diplomacy/Letter.java](/src/main/java/name/kingdoms/diplomacy/Letter.java) | Java | 70 | 2 | 12 | 84 |
| [src/main/java/name/kingdoms/network/networkInit.java](/src/main/java/name/kingdoms/network/networkInit.java) | Java | 194 | 15 | 55 | 264 |
| [src/main/java/name/kingdoms/payload/kingdomHoverSyncS2CPayload.java](/src/main/java/name/kingdoms/payload/kingdomHoverSyncS2CPayload.java) | Java | 9 | 4 | 4 | 17 |
| [src/main/java/name/kingdoms/sim/SimRunWriter.java](/src/main/java/name/kingdoms/sim/SimRunWriter.java) | Java | 204 | 37 | 42 | 283 |
| [src/main/java/name/kingdoms/war/WarState.java](/src/main/java/name/kingdoms/war/WarState.java) | Java | 36 | 2 | 12 | 50 |

[Summary](results.md) / [Details](details.md) / [Diff Summary](diff.md) / Diff Details