"""
암호화 모델 필드 (09 문서 2-7, 03 문서 §8 "전화번호·건강정보 컬럼 암호화")

DB에는 ``enc1:`` 접두어가 붙은 암호문만 저장되고, 모델 인스턴스에서는 평문으로 다룬다.
암호문은 매번 달라지므로 **정확히 일치 검색이 불가능**하다. 조회가 필요한 값은 별도 해시 색인을 둔다
(예: User.phone_hash).
"""
import json

from django.core import validators
from django.db import models

from . import crypto


class EncryptedTextField(models.TextField):
    """문자열을 암호화해 저장하는 필드"""

    description = '암호화된 문자열'

    def from_db_value(self, value, expression, connection):
        if value is None or value == '':
            return value
        return crypto.decrypt_str(value)

    def to_python(self, value):
        if isinstance(value, str) and crypto.is_encrypted(value):
            return crypto.decrypt_str(value)
        return super().to_python(value)

    def get_prep_value(self, value):
        value = super().get_prep_value(value)
        if value is None or value == '':
            return value
        if crypto.is_encrypted(value):
            return value
        return crypto.encrypt_str(value)


class EncryptedCharField(EncryptedTextField):
    """길이 검증이 있는 암호화 문자열(DB 컬럼은 text — 암호문이 평문보다 길다)"""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        if self.max_length is not None:
            self.validators.append(validators.MaxLengthValidator(self.max_length))


class EncryptedJSONField(models.TextField):
    """JSON 직렬화 값(목록·사전)을 암호화해 저장하는 필드"""

    description = '암호화된 JSON'

    def from_db_value(self, value, expression, connection):
        if value is None or value == '':
            return None
        return json.loads(crypto.decrypt_str(value))

    def to_python(self, value):
        if isinstance(value, str):
            if value == '':
                return None
            return json.loads(crypto.decrypt_str(value))
        return value

    def get_prep_value(self, value):
        if value is None:
            return None
        return crypto.encrypt_str(json.dumps(value, ensure_ascii=False, sort_keys=True))

    def value_to_string(self, obj):
        return json.dumps(self.value_from_object(obj), ensure_ascii=False)
