package io.opencubes.db.sql

class UnsupportedDriver(name: String) : Exception("There is no driver for $name")
