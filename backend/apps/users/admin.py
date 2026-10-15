from django.contrib import admin

from .models import FirebaseDeletionTask


@admin.register(FirebaseDeletionTask)
class FirebaseDeletionTaskAdmin(admin.ModelAdmin):
    """탈퇴자 Firebase 삭제 대기열(법적 검토 D-05) — 조회만, 재시도는 retry_firebase_deletions"""
    list_display = ('id', 'created_at', 'attempts', 'last_attempt_at', 'last_error')
    readonly_fields = [f.name for f in FirebaseDeletionTask._meta.fields]
    exclude = ()

    def has_add_permission(self, request):
        return False

    def has_change_permission(self, request, obj=None):
        return False
