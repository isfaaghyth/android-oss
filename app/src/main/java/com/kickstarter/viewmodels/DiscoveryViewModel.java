package com.kickstarter.viewmodels;

import android.content.Intent;
import android.support.annotation.NonNull;
import android.util.Pair;

import com.kickstarter.libs.ActivityViewModel;
import com.kickstarter.libs.BuildCheck;
import com.kickstarter.libs.Config;
import com.kickstarter.libs.CurrentConfigType;
import com.kickstarter.libs.CurrentUserType;
import com.kickstarter.libs.Environment;
import com.kickstarter.libs.FeatureKey;
import com.kickstarter.libs.utils.BooleanUtils;
import com.kickstarter.libs.utils.DiscoveryDrawerUtils;
import com.kickstarter.libs.utils.DiscoveryUtils;
import com.kickstarter.libs.utils.IntegerUtils;
import com.kickstarter.libs.utils.ObjectUtils;
import com.kickstarter.models.Category;
import com.kickstarter.models.User;
import com.kickstarter.services.ApiClientType;
import com.kickstarter.services.DiscoveryParams;
import com.kickstarter.services.WebClientType;
import com.kickstarter.services.apiresponses.InternalBuildEnvelope;
import com.kickstarter.ui.activities.DiscoveryActivity;
import com.kickstarter.ui.adapters.DiscoveryPagerAdapter;
import com.kickstarter.ui.adapters.data.NavigationDrawerData;
import com.kickstarter.ui.intentmappers.DiscoveryIntentMapper;
import com.kickstarter.ui.intentmappers.IntentMapper;
import com.kickstarter.ui.viewholders.discoverydrawer.ChildFilterViewHolder;
import com.kickstarter.ui.viewholders.discoverydrawer.LoggedInViewHolder;
import com.kickstarter.ui.viewholders.discoverydrawer.LoggedOutViewHolder;
import com.kickstarter.ui.viewholders.discoverydrawer.ParentFilterViewHolder;
import com.kickstarter.ui.viewholders.discoverydrawer.TopFilterViewHolder;
import com.kickstarter.viewmodels.inputs.DiscoveryViewModelInputs;
import com.kickstarter.viewmodels.outputs.DiscoveryViewModelOutputs;

import java.util.List;

import rx.Observable;
import rx.subjects.BehaviorSubject;
import rx.subjects.PublishSubject;

import static com.kickstarter.libs.rx.transformers.Transformers.combineLatestPair;
import static com.kickstarter.libs.rx.transformers.Transformers.neverError;
import static com.kickstarter.libs.rx.transformers.Transformers.takeWhen;
import static com.kickstarter.libs.utils.ObjectUtils.coalesce;

public final class DiscoveryViewModel extends ActivityViewModel<DiscoveryActivity> implements DiscoveryViewModelInputs,
  DiscoveryViewModelOutputs {
  private final ApiClientType apiClient;
  private final CurrentConfigType currentConfig;
  private final WebClientType webClient;
  private final BuildCheck buildCheck;
  private final CurrentUserType currentUser;

  public DiscoveryViewModel(final @NonNull Environment environment) {
    super(environment);

    this.apiClient = environment.apiClient();
    this.buildCheck = environment.buildCheck();
    this.currentConfig = environment.currentConfig();
    this.currentUser = environment.currentUser();
    this.webClient = environment.webClient();

    this.buildCheck.bind(this, this.webClient);

    this.showBuildCheckAlert = this.newerBuildIsAvailable;
    this.showInternalTools = this.internalToolsClick;
    this.showLoginTout = this.loggedOutLoginToutClick;
    this.showProfile = this.profileClick;
    this.showSettings = this.settingsClick;

    final Observable<Boolean> userIsCreator = this.currentUser.observable()
      .map(u -> u != null && IntegerUtils.isNonZero(u.createdProjectsCount()));

    final Observable<Boolean> creatorViewFeatureFlagIsEnabled = this.currentConfig.observable()
      .map(Config::features)
      .filter(ObjectUtils::isNotNull)
      .map(f -> coalesce(f.get(FeatureKey.ANDROID_CREATOR_VIEW), false));

    this.creatorDashboardButtonIsGone = Observable.combineLatest(
      userIsCreator,
      creatorViewFeatureFlagIsEnabled,
      Pair::create
    )
      .map(isCreatorAndViewDash -> !isCreatorAndViewDash.first || !isCreatorAndViewDash.second);

    // Seed params when we are freshly launching the app with no data.
    final Observable<DiscoveryParams> paramsFromInitialIntent = intent()
      .take(1)
      .map(Intent::getAction)
      .filter(Intent.ACTION_MAIN::equals)
      .map(__ -> DiscoveryParams.builder().build())
      .share();

    final Observable<DiscoveryParams> paramsFromIntent = intent()
      .flatMap(i -> DiscoveryIntentMapper.params(i, this.apiClient));

    final Observable<DiscoveryParams> drawerParamsClicked = this.childFilterRowClick
      .mergeWith(this.topFilterRowClick)
      .map(NavigationDrawerData.Section.Row::params);

    // Merge various param data sources.
    final Observable<DiscoveryParams> params = Observable.merge(
      paramsFromInitialIntent,
      paramsFromIntent,
      drawerParamsClicked
    );

    final Observable<Integer> pagerSelectedPage = this.pagerSetPrimaryPage.distinctUntilChanged();

    // Combine params with the selected sort position.
    Observable.combineLatest(
      params,
      pagerSelectedPage.map(DiscoveryUtils::sortFromPosition),
      (p, s) -> p.toBuilder().sort(s).build()
    )
      .compose(bindToLifecycle())
      .subscribe(this.updateParamsForPage);

    final Observable<List<Category>> categories = this.apiClient.fetchCategories()
      .compose(neverError())
      .flatMap(Observable::from)
      .toSortedList()
      .share();

    // Combine root categories with the selected sort position.
    Observable.combineLatest(
      categories
        .flatMap(Observable::from)
        .filter(Category::isRoot)
        .toList(),
      pagerSelectedPage,
      Pair::create
    )
      .compose(bindToLifecycle())
      .subscribe(this.rootCategoriesAndPosition);

    final Observable<Category> drawerClickedParentCategory = this.parentFilterRowClick
      .map(NavigationDrawerData.Section.Row::params)
      .map(DiscoveryParams::category);

    final Observable<Category> expandedCategory = Observable.merge(
      this.topFilterRowClick.map(__ -> (Category) null),
        drawerClickedParentCategory
      )
      .scan(null, (previous, next) -> {
        if (previous != null && next != null && previous.equals(next)) {
          return null;
        }
        return next;
      });

    // Accumulate a list of pages to clear when the params or user changes,
    // to avoid displaying old data.
    pagerSelectedPage
      .compose(takeWhen(params))
      .compose(combineLatestPair(this.currentUser.observable()))
      .map(pageAndUser -> pageAndUser.first)
      .flatMap(currentPage -> Observable.from(DiscoveryParams.Sort.values())
        .map(DiscoveryUtils::positionFromSort)
        .filter(sortPosition -> !sortPosition.equals(currentPage))
        .toList()
      )
      .compose(bindToLifecycle())
      .subscribe(this.clearPages);

    params.distinctUntilChanged()
      .compose(bindToLifecycle())
      .subscribe(this.updateToolbarWithParams);

    this.updateParamsForPage.map(__ -> true)
      .compose(bindToLifecycle())
      .subscribe(this.expandSortTabLayout);

    Observable.combineLatest(
      categories,
      params,
      expandedCategory,
      this.currentUser.observable(),
      DiscoveryDrawerUtils::deriveNavigationDrawerData
    )
      .distinctUntilChanged()
      .compose(bindToLifecycle())
      .subscribe(this.navigationDrawerData);

    drawerParamsClicked
      .compose(bindToLifecycle())
      .subscribe(this.koala::trackDiscoveryFilterSelected);

    Observable.merge(
      this.openDrawer,
      this.childFilterRowClick.map(__ -> false),
      this.topFilterRowClick.map(__ -> false),
      this.internalToolsClick.map(__ -> false),
      this.loggedOutLoginToutClick.map(__ -> false),
      this.profileClick.map(__ -> false),
      this.settingsClick.map(__ -> false)
    )
      .distinctUntilChanged()
      .compose(bindToLifecycle())
      .subscribe(this.drawerIsOpen);

    this.openDrawer
      .filter(BooleanUtils::isTrue)
      .compose(bindToLifecycle())
      .subscribe(__ -> this.koala.trackDiscoveryFilters());

    intent()
      .filter(IntentMapper::appBannerIsSet)
      .compose(bindToLifecycle())
      .subscribe(__ -> this.koala.trackOpenedAppBanner());
  }

  private final PublishSubject<NavigationDrawerData.Section.Row> childFilterRowClick = PublishSubject.create();
  private final PublishSubject<Void> internalToolsClick = PublishSubject.create();
  private final PublishSubject<Void> loggedOutLoginToutClick = PublishSubject.create();
  private final PublishSubject<InternalBuildEnvelope> newerBuildIsAvailable = PublishSubject.create();
  private final PublishSubject<Boolean> openDrawer = PublishSubject.create();
  private final PublishSubject<Integer> pagerSetPrimaryPage = PublishSubject.create();
  private final PublishSubject<NavigationDrawerData.Section.Row> parentFilterRowClick = PublishSubject.create();
  private final PublishSubject<Void> profileClick = PublishSubject.create();
  private final PublishSubject<Void> settingsClick = PublishSubject.create();
  private final PublishSubject<NavigationDrawerData.Section.Row> topFilterRowClick = PublishSubject.create();

  private final Observable<Boolean> creatorDashboardButtonIsGone;
  private final BehaviorSubject<List<Integer>> clearPages = BehaviorSubject.create();
  private final BehaviorSubject<Boolean> drawerIsOpen = BehaviorSubject.create();
  private final BehaviorSubject<Boolean> expandSortTabLayout = BehaviorSubject.create();
  private final BehaviorSubject<NavigationDrawerData> navigationDrawerData = BehaviorSubject.create();
  private final BehaviorSubject<Pair<List<Category>, Integer>> rootCategoriesAndPosition = BehaviorSubject.create();
  private final Observable<InternalBuildEnvelope> showBuildCheckAlert;
  private final Observable<Void> showInternalTools;
  private final Observable<Void> showLoginTout;
  private final Observable<Void> showProfile;
  private final Observable<Void> showSettings;
  private final BehaviorSubject<DiscoveryParams> updateParamsForPage = BehaviorSubject.create();
  private final BehaviorSubject<DiscoveryParams> updateToolbarWithParams = BehaviorSubject.create();

  public final DiscoveryViewModelInputs inputs = this;
  public final DiscoveryViewModelOutputs outputs = this;

  @Override public void childFilterViewHolderRowClick(final @NonNull ChildFilterViewHolder viewHolder, final @NonNull NavigationDrawerData.Section.Row row) {
    this.childFilterRowClick.onNext(row);
  }
  @Override public void discoveryPagerAdapterSetPrimaryPage(final @NonNull DiscoveryPagerAdapter adapter, final int position) {
    this.pagerSetPrimaryPage.onNext(position);
  }
  @Override public void loggedInViewHolderInternalToolsClick(final @NonNull LoggedInViewHolder viewHolder) {
    this.internalToolsClick.onNext(null);
  }
  @Override public void loggedInViewHolderProfileClick(final @NonNull LoggedInViewHolder viewHolder, final @NonNull User user) {
    this.profileClick.onNext(null);
  }
  @Override public void loggedInViewHolderSettingsClick(final @NonNull LoggedInViewHolder viewHolder, final @NonNull User user) {
    this.settingsClick.onNext(null);
  }
  @Override public void loggedOutViewHolderInternalToolsClick(final @NonNull LoggedOutViewHolder viewHolder) {
    this.internalToolsClick.onNext(null);
  }
  @Override public void loggedOutViewHolderLoginToutClick(final @NonNull LoggedOutViewHolder viewHolder) {
    this.loggedOutLoginToutClick.onNext(null);
  }
  @Override public void newerBuildIsAvailable(final @NonNull InternalBuildEnvelope envelope) {
    this.newerBuildIsAvailable.onNext(envelope);
  }
  @Override public void openDrawer(final boolean open) {
    this.openDrawer.onNext(open);
  }
  @Override public void parentFilterViewHolderRowClick(final @NonNull ParentFilterViewHolder viewHolder, final @NonNull NavigationDrawerData.Section.Row row) {
    this.parentFilterRowClick.onNext(row);
  }
  @Override public void topFilterViewHolderRowClick(final @NonNull TopFilterViewHolder viewHolder, final @NonNull NavigationDrawerData.Section.Row row) {
    this.topFilterRowClick.onNext(row);
  }

  @Override public @NonNull Observable<List<Integer>> clearPages() {
    return this.clearPages;
  }
  @Override public @NonNull Observable<Boolean> creatorDashboardButtonIsGone() {
    return this.creatorDashboardButtonIsGone;
  }
  @Override public @NonNull Observable<Boolean> drawerIsOpen() {
    return this.drawerIsOpen;
  }
  @Override public @NonNull Observable<Boolean> expandSortTabLayout() {
    return this.expandSortTabLayout;
  }
  @Override public @NonNull Observable<NavigationDrawerData> navigationDrawerData() {
    return this.navigationDrawerData;
  }
  @Override public @NonNull Observable<Pair<List<Category>, Integer>> rootCategoriesAndPosition() {
    return this.rootCategoriesAndPosition;
  }
  @Override public @NonNull Observable<InternalBuildEnvelope> showBuildCheckAlert() {
    return this.showBuildCheckAlert;
  }
  @Override public @NonNull Observable<Void> showInternalTools() {
    return this.showInternalTools;
  }
  @Override public @NonNull Observable<Void> showLoginTout() {
    return this.showLoginTout;
  }
  @Override public @NonNull Observable<Void> showProfile() {
    return this.showProfile;
  }
  @Override public @NonNull Observable<Void> showSettings() {
    return this.showSettings;
  }
  @Override public @NonNull Observable<DiscoveryParams> updateParamsForPage() {
    return this.updateParamsForPage;
  }
  @Override public @NonNull Observable<DiscoveryParams> updateToolbarWithParams() {
    return this.updateToolbarWithParams;
  }
}
