# Hereabouts app

This is the code of the Hereabouts phone app for iPhone and Android. The app shows which plants are native to your county and where to buy them.

## Where this code comes from

This project is a copy. The app is written in a private project, and each change there is copied here as a new snapshot. Nothing is edited here, so pull requests and issues are not accepted.

## Licence

No licence is granted, so all rights are reserved.

The two fonts in `shared/fonts/` (copied into `shared/bundle/fonts/`) are the exception. Each is shared under the SIL Open Font License, and its notice is in the same folder (`OFL-Figtree.txt`, `OFL-YoungSerif.txt`).

## Folders

- `ios/`: the iPhone app. `ios/HereaboutsCore` holds its logic as a Swift package.
- `android/`: the Android app, a Gradle project. `android/core` holds its logic.
- `shared/bundle/`: the data both apps ship with, and `expected/`, the answers the website's own code gives for the same questions.
- `.github/workflows/`: the builds and tests.

## Tests

The logic tests check that each app gives the same answers as the website, case by case, against the files in `shared/bundle/expected/`. They run on every snapshot, in GitHub Actions.

To run them yourself, from the top folder:

- iPhone logic, with Swift 6.2: `cd ios/HereaboutsCore` then `swift test`
- Android logic, with Java 21: `cd android` then `./gradlew :core:test`
