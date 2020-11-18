/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row.wrapper;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.View;

import com.android.systemui.statusbar.notification.ImageTransformState;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * Wraps a notification containing a big picture template
 */
public class NotificationBigPictureTemplateViewWrapper extends NotificationTemplateViewWrapper {

    protected NotificationBigPictureTemplateViewWrapper(Context ctx, View view,
            ExpandableNotificationRow row) {
        super(ctx, view, row);
    }

    @Override
    public void onContentUpdated(ExpandableNotificationRow row) {
        super.onContentUpdated(row);
        updateImageTag(row.getEntry().getSbn());
        // Round the corners of the big picture content
        mView.findViewById(com.android.internal.R.id.big_picture).setClipToOutline(true);
    }

    private void updateImageTag(StatusBarNotification notification) {
        final Bundle extras = notification.getNotification().extras;
        Icon overRiddenIcon = extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG);
        if (overRiddenIcon != null) {
            mPicture.setTag(ImageTransformState.ICON_TAG, overRiddenIcon);
        }
    }
}
