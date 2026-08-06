@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release-android.ps1" %*
