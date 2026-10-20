package com.example.error

import org.babyfish.jimmer.error.ErrorFamily
import org.babyfish.jimmer.error.ErrorField

@ErrorFamily
enum class UserInfoErrorCode {
    @ErrorField(name = "illegalChars", type = Char::class, list = true)
    ILLEGAL_USER_NAME,
    PASSWORD_TOO_SHORT,
    PASSWORDS_NOT_SAME
}
