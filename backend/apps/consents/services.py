"""
동의 상태 계산·기록

- 현재 문서: 항목별로 사용 중(is_active)이고 시행일이 지난(오늘 포함) 문서 중 시행일이 가장 늦은 것
  (예전에는 미래 시행일 개정본이 등록 즉시 현재 문서가 되어 사전 고지 없이 재동의를 강제했다 — 배포 리허설 B-10)
- 동의 상태: 항목별 최신 기록이 '동의'이고 그 버전이 현재 문서 버전과 같아야 유효(문서가 바뀌면 재동의 필요)
- 민감정보(SENSITIVE) 철회 시 ``sensitive_consent_withdrawn`` 신호 → 건강정보·질환 기반 점수 삭제
"""
import json
from pathlib import Path

from django.db import transaction
from django.dispatch import Signal
from django.utils import timezone

from .models import ConsentDocument, ConsentRecord, ConsentType, DocumentKind

DATA_FILE = Path(__file__).resolve().parent / 'data' / 'documents.json'

# sender=ConsentRecord, user=<User> — 수신자는 삭제한 데이터 종류 목록을 돌려준다
sensitive_consent_withdrawn = Signal()


def load_documents(document_model=ConsentDocument):
    """data/documents.json 내용을 DB에 반영(있으면 갱신). 마이그레이션과 sync_reference_data가 같이 쓴다."""
    data = json.loads(DATA_FILE.read_text(encoding='utf-8'))
    # 과거 마이그레이션의 모델에는 나중에 생긴 필드(kind 등)가 없을 수 있다
    fields = {f.name for f in document_model._meta.get_fields()}
    for doc in data['documents']:
        defaults = {
            'title': doc['title'], 'summary': doc.get('summary', ''), 'url': doc.get('url', ''),
            'required': doc['required'], 'effective_at': doc['effective_at'],
            'is_active': doc.get('is_active', True), 'kind': doc.get('kind', 'CONSENT'),
        }
        document_model.objects.update_or_create(
            type=doc['type'], version=doc['version'],
            defaults={k: v for k, v in defaults.items() if k in fields},
        )


def active_documents():
    """항목별 현재 문서. 시행일이 오늘(한국 시간) 이후인 개정본은 아직 현재 문서가 아니다(사전 고지 기간)."""
    from apps.users.profile import local_today
    docs = {}
    qs = ConsentDocument.objects.filter(is_active=True, effective_at__lte=local_today())
    for doc in qs.order_by('type', '-effective_at', '-id'):
        docs.setdefault(doc.type, doc)
    return docs


def latest_records(user):
    records = {}
    for rec in ConsentRecord.objects.filter(user=user).order_by('type', '-recorded_at', '-id'):
        records.setdefault(rec.type, rec)
    return records


def _is_valid(doc, rec):
    return bool(doc and rec and rec.agreed and rec.withdrawn_at is None and rec.version == doc.version)


def status(user):
    docs, recs = active_documents(), latest_records(user)
    items = []
    for ctype in ConsentType.values:
        doc = docs.get(ctype)
        if doc is None:
            continue
        rec = recs.get(ctype)
        items.append({
            'type': ctype, 'kind': doc.kind, 'title': doc.title, 'required': doc.required, 'current_version': doc.version,
            'agreed': _is_valid(doc, rec),
            'agreed_version': rec.version if rec and rec.agreed else None,
            'agreed_at': rec.recorded_at if rec and rec.agreed else None,
            'method': rec.method if rec else None,
            'needs_reconsent': bool(rec and rec.agreed and rec.version != doc.version),
        })
    return {'items': items, 'all_required_agreed': all(i['agreed'] for i in items if i['required'])}


def missing_required(user):
    docs, recs = active_documents(), latest_records(user)
    return [t for t, doc in docs.items() if doc.required and not _is_valid(doc, recs.get(t))]


def has_consent(user, ctype) -> bool:
    if not getattr(user, 'is_authenticated', False):
        return False
    doc = active_documents().get(ctype)
    rec = ConsentRecord.objects.filter(user=user, type=ctype).order_by('-recorded_at', '-id').first()
    return _is_valid(doc, rec)


def has_sensitive_consent(user) -> bool:
    return has_consent(user, ConsentType.SENSITIVE)


class ConsentError(Exception):
    def __init__(self, code, message, field_errors=None):
        super().__init__(message)
        self.code, self.message, self.field_errors = code, message, field_errors


@transaction.atomic
def record_decisions(user, items):
    """
    items: [{'type', 'version', 'agreed', 'method'}]
    반환: 삭제한 데이터 종류 목록(민감정보 철회 시 ['diet_scores', 'health_profile'])
    """
    docs = active_documents()
    previous = latest_records(user)
    for item in items:
        doc = docs.get(item['type'])
        if doc is None:
            raise ConsentError('UNKNOWN_CONSENT', '지금 받을 수 없는 동의 항목이에요.',
                               {item['type']: ['사용하지 않는 동의 항목입니다.']})
        if item['version'] != doc.version:
            raise ConsentError('VERSION_MISMATCH', '동의 문서가 바뀌었어요. 새 내용을 다시 확인해 주세요.',
                               {item['type']: [f'현재 버전은 {doc.version} 입니다.']})
        if doc.kind == DocumentKind.NOTICE and not item['agreed']:
            raise ConsentError('NOTICE_ACK_ONLY', '고지는 확인만 할 수 있어요. 원하지 않으면 회원 탈퇴를 이용해 주세요.',
                               {item['type']: ['고지 항목은 확인(agreed=true)만 기록합니다.']})
        if doc.required and not item['agreed']:
            raise ConsentError('REQUIRED_CONSENT', '필수 동의는 철회할 수 없어요. 이용을 멈추려면 회원 탈퇴를 이용해 주세요.',
                               {item['type']: ['필수 동의 항목입니다.']})
    now = timezone.now()
    deleted = []
    for item in items:
        prev = previous.get(item['type'])
        if not item['agreed'] and prev and prev.agreed and prev.withdrawn_at is None:
            prev.withdrawn_at = now
            prev.save(update_fields=['withdrawn_at'])
        ConsentRecord.objects.create(user=user, type=item['type'], version=item['version'],
                                     agreed=item['agreed'], method=item['method'],
                                     app_version=item.get('app_version', ''), screen_id=item.get('screen_id', ''),
                                     voice_confirmed=item.get('voice_confirmed'))
        if item['type'] == ConsentType.SENSITIVE and not item['agreed']:
            for _, value in sensitive_consent_withdrawn.send(sender=ConsentRecord, user=user):
                deleted.extend(value or [])
    return sorted(set(deleted))
