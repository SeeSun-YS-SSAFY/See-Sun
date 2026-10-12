from django.urls import path

from . import views

app_name = 'diet'

urlpatterns = [
    path('diet/evaluate/', views.EvaluateView.as_view(), name='evaluate'),
    path('diet/score/', views.DailyScoreView.as_view(), name='score'),
    path('diet/stats/', views.StatsView.as_view(), name='stats'),
    path('meals/bulk/', views.MealBulkView.as_view(), name='meal_bulk'),
    path('meals/', views.MealListView.as_view(), name='meal_list'),
    path('meals/<uuid:meal_id>/', views.MealDetailView.as_view(), name='meal_detail'),
    path('recommendations/', views.RecommendationView.as_view(), name='recommendations'),
    path('favorites/foods/', views.FavoriteListView.as_view(), name='favorite_list'),
    path('favorites/foods/<int:food_id>/', views.FavoriteDetailView.as_view(), name='favorite_detail'),
]
