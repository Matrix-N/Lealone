/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.storage.page;

public interface IPageReference {

    IPage getOrReadPage();

    void markDirtyBottomUp();

    PageListener getPageListener();

}