from django.urls import path

from .views import ConsentDocumentListView, MyConsentsView

app_name = 'consents'

urlpatterns = [
    path('consents/documents/', ConsentDocumentListView.as_view(), name='documents'),
    path('me/consents/', MyConsentsView.as_view(), name='my_consents'),
]
