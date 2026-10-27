package com.watchjobs.exception

class NotFoundException(message: String) : RuntimeException(message)

class ConflictException(message: String) : RuntimeException(message)

class BusinessException(message: String) : RuntimeException(message)

class TooManyRequestsException(message: String) : RuntimeException(message)
