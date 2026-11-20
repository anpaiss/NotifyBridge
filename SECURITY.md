# Security Policy

## Supported versions

Only the latest release is supported. Please update to the most recent version
before reporting an issue.

## Reporting a vulnerability

Please do **not** open a public issue for security problems.

Report them privately through GitHub's private vulnerability reporting:
open the **Security** tab of this repository and click **Report a vulnerability**.

You can expect an acknowledgement within a few days. Fixes are published as a
new release; the report is disclosed publicly only after a fix is available.

## Scope

NotifyBridge is two apps:

- a phone app that reads notifications through Android's notification access,
  keeps those of the apps, people and groups the user switched on, and sends
  them to the Karoo as Bluetooth Low Energy notifications of a GATT service it
  hosts. It has no internet permission;
- a Hammerhead Karoo extension that connects to that service, shows the
  messages in a popup drawn over the ride screen (overlay permission) or as a
  Karoo alert or notification. It has no internet permission either.

The two apps pair once through a characteristic of that service: an ECDH
P-256 exchange with a commitment and a six-digit code compared by the user.
After that the messages and the link check are sealed with AES-256-GCM under
the shared key; an unpaired Karoo cannot read them. The details are in the
README, "Pairing and encryption, in detail".

Reports about the apps' own code are in scope: the GATT service and who can
read from it, the pairing and the encryption, the handling of notification contents, the exported components,
the files in the apps' private storage. Issues in Android, the Karoo OS or the
Karoo extension SDK should be reported to Google or Hammerhead.
