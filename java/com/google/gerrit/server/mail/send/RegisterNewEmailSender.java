// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gerrit.server.mail.send;

import static java.util.Objects.requireNonNull;

import com.google.gerrit.exceptions.EmailException;
import com.google.gerrit.extensions.api.changes.RecipientType;
import com.google.gerrit.mail.Address;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.mail.EmailTokenVerifier;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

/**
 * Sender that informs a user by email about the registration of a new email address for their
 * account.
 */
public class RegisterNewEmailSender extends OutgoingEmail {
  public interface Factory {
    RegisterNewEmailSender create(String address);
  }

  private final EmailTokenVerifier tokenVerifier;
  private final IdentifiedUser user;
  private final String addr;
  private String emailToken;

  @Inject
  public RegisterNewEmailSender(
      EmailArguments args,
      EmailTokenVerifier tokenVerifier,
      IdentifiedUser callingUser,
      @Assisted final String address) {
    super(args, "registernewemail");
    this.tokenVerifier = tokenVerifier;
    this.user = callingUser;
    this.addr = address;
  }

  @Override
  protected void init() throws EmailException {
    super.init();
    setHeader("Subject", "[Gerrit Code Review] Email Verification");
    add(RecipientType.TO, new Address(addr));
  }

  @Override
  protected void format() throws EmailException {
    appendText(textTemplate("RegisterNewEmail"));
  }

  public boolean isAllowed() {
    return args.emailSender.canEmail(addr);
  }

  @Override
  protected void setupSoyContext() {
    super.setupSoyContext();
    soyContextEmailData.put("emailRegistrationToken", getEmailRegistrationToken());
    soyContextEmailData.put("userNameEmail", getUserNameEmailFor(user.getAccountId()));
  }

  private String getEmailRegistrationToken() {
    if (emailToken == null) {
      emailToken = requireNonNull(tokenVerifier.encode(user.getAccountId(), addr), "token");
    }
    return emailToken;
  }
}
