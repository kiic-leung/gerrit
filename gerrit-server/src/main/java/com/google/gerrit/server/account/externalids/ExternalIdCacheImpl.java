// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.account.externalids;

import static java.util.stream.Collectors.toSet;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gerrit.reviewdb.client.Account;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.eclipse.jgit.lib.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caches external IDs of all accounts. The external IDs are always loaded from NoteDb. */
@Singleton
class ExternalIdCacheImpl implements ExternalIdCache {
  private static final Logger log = LoggerFactory.getLogger(ExternalIdCacheImpl.class);

  public static final String CACHE_NAME = "external_ids_map";

  private final LoadingCache<ObjectId, ImmutableSetMultimap<Account.Id, ExternalId>>
      extIdsByAccount;
  private final ExternalIdReader externalIdReader;
  private final Lock lock;

  @Inject
  ExternalIdCacheImpl(
      @Named(CACHE_NAME)
          LoadingCache<ObjectId, ImmutableSetMultimap<Account.Id, ExternalId>> extIdsByAccount,
      ExternalIdReader externalIdReader) {
    this.extIdsByAccount = extIdsByAccount;
    this.externalIdReader = externalIdReader;
    this.lock = new ReentrantLock(true /* fair */);
  }

  @Override
  public void onCreate(ObjectId newNotesRev, Collection<ExternalId> extIds) throws IOException {
    updateCache(
        newNotesRev,
        m -> {
          for (ExternalId extId : extIds) {
            m.put(extId.accountId(), extId);
          }
        });
  }

  @Override
  public void onRemove(ObjectId newNotesRev, Collection<ExternalId> extIds) throws IOException {
    updateCache(
        newNotesRev,
        m -> {
          for (ExternalId extId : extIds) {
            m.remove(extId.accountId(), extId);
          }
        });
  }

  @Override
  public void onRemoveByKeys(
      ObjectId newNotesRev, Account.Id accountId, Collection<ExternalId.Key> extIdKeys)
      throws IOException {
    updateCache(newNotesRev, m -> removeKeys(m.get(accountId), extIdKeys));
  }

  @Override
  public void onRemoveByKeys(ObjectId newNotesRev, Collection<ExternalId.Key> extIdKeys)
      throws IOException {
    updateCache(newNotesRev, m -> removeKeys(m.values(), extIdKeys));
  }

  @Override
  public void onUpdate(ObjectId newNotesRev, Collection<ExternalId> updatedExtIds)
      throws IOException {
    updateCache(
        newNotesRev,
        m -> {
          removeKeys(m.values(), updatedExtIds.stream().map(e -> e.key()).collect(toSet()));
          for (ExternalId updatedExtId : updatedExtIds) {
            m.put(updatedExtId.accountId(), updatedExtId);
          }
        });
  }

  @Override
  public void onReplace(
      ObjectId newNotesRev,
      Account.Id accountId,
      Collection<ExternalId> toRemove,
      Collection<ExternalId> toAdd)
      throws IOException {
    ExternalIdsUpdate.checkSameAccount(Iterables.concat(toRemove, toAdd), accountId);

    updateCache(
        newNotesRev,
        m -> {
          for (ExternalId extId : toRemove) {
            m.remove(extId.accountId(), extId);
          }
          for (ExternalId extId : toAdd) {
            m.put(extId.accountId(), extId);
          }
        });
  }

  @Override
  public void onReplaceByKeys(
      ObjectId newNotesRev,
      Account.Id accountId,
      Collection<ExternalId.Key> toRemove,
      Collection<ExternalId> toAdd)
      throws IOException {
    ExternalIdsUpdate.checkSameAccount(toAdd, accountId);

    updateCache(
        newNotesRev,
        m -> {
          removeKeys(m.get(accountId), toRemove);
          for (ExternalId extId : toAdd) {
            m.put(extId.accountId(), extId);
          }
        });
  }

  @Override
  public void onReplaceByKeys(
      ObjectId newNotesRev, Collection<ExternalId.Key> toRemove, Collection<ExternalId> toAdd)
      throws IOException {
    updateCache(
        newNotesRev,
        m -> {
          removeKeys(m.values(), toRemove);
          for (ExternalId extId : toAdd) {
            m.put(extId.accountId(), extId);
          }
        });
  }

  @Override
  public void onReplace(
      ObjectId newNotesRev, Collection<ExternalId> toRemove, Collection<ExternalId> toAdd)
      throws IOException {
    updateCache(
        newNotesRev,
        m -> {
          for (ExternalId extId : toRemove) {
            m.remove(extId.accountId(), extId);
          }
          for (ExternalId extId : toAdd) {
            m.put(extId.accountId(), extId);
          }
        });
  }

  @Override
  public Set<ExternalId> byAccount(Account.Id accountId) throws IOException {
    try {
      return extIdsByAccount.get(externalIdReader.readRevision()).get(accountId);
    } catch (ExecutionException e) {
      throw new IOException("Cannot list external ids by account", e);
    }
  }

  @Override
  public Set<ExternalId> byEmail(String email) throws IOException {
    try {
      return extIdsByAccount
          .get(externalIdReader.readRevision())
          .values()
          .stream()
          .filter(e -> email.equals(e.email()))
          .collect(toSet());
    } catch (ExecutionException e) {
      throw new IOException("Cannot list external ids by email", e);
    }
  }

  private void updateCache(ObjectId newNotesRev, Consumer<Multimap<Account.Id, ExternalId>> update)
      throws IOException {
    lock.lock();
    try {
      ListMultimap<Account.Id, ExternalId> m =
          MultimapBuilder.hashKeys()
              .arrayListValues()
              .build(extIdsByAccount.get(externalIdReader.readRevision()));
      update.accept(m);
      extIdsByAccount.put(newNotesRev, ImmutableSetMultimap.copyOf(m));
    } catch (ExecutionException e) {
      log.warn("Cannot update external IDs", e);
    } finally {
      lock.unlock();
    }
  }

  private static void removeKeys(Collection<ExternalId> ids, Collection<ExternalId.Key> toRemove) {
    Collections2.transform(ids, e -> e.key()).removeAll(toRemove);
  }

  static class Loader extends CacheLoader<ObjectId, ImmutableSetMultimap<Account.Id, ExternalId>> {
    private final ExternalIdReader externalIdReader;

    @Inject
    Loader(ExternalIdReader externalIdReader) {
      this.externalIdReader = externalIdReader;
    }

    @Override
    public ImmutableSetMultimap<Account.Id, ExternalId> load(ObjectId notesRev) throws Exception {
      Multimap<Account.Id, ExternalId> extIdsByAccount =
          MultimapBuilder.hashKeys().arrayListValues().build();
      for (ExternalId extId : externalIdReader.all(notesRev)) {
        extIdsByAccount.put(extId.accountId(), extId);
      }
      return ImmutableSetMultimap.copyOf(extIdsByAccount);
    }
  }
}
