# Playback Reports candidate

Base: production source db00197 (3.8.14 / 10034). Production version, package and signing unchanged.
The separate Preview build uses versionName 3.8.14-reports-test; QA reports go to DemoData only.

Android files: PlayerActivity.java, PlayerIcon.java, PlaybackReport.java, ReportsApi.java;
DemoData.java for offline fixtures; PlaybackReportTest.java for payload/endpoint validation.
Backend files and installation: panel/onepanel-playback-reports/README.txt.

The UI captures current IDs/position before pausing, confirms submission, sends asynchronously,
and restores focus and previous play state. Report callbacks cannot reopen UI after stop/close.
No polling, offline queue, automatic downloads, file repairs or admin actions in the APK.
No production release is authorized by this candidate branch. Do not merge or publish automatically.

Validation: .github/workflows/playback-reports.yml. MySQL 8 and MariaDB 10.11 backend tests,
Java unit tests and a separate Preview build; focused Android TV QA on API 28/720p and API31/1080p.
Check actual workflow results and screenshots before declaring readiness.

The prior Android series-recent-sort experiment is not included. Its underlying issue was fixed
and TV-verified separately on the content backend by adding a library-addition timestamp.
Do not overwrite that deployed backend correction with old source.
