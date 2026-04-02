package com.example.smartfarm;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class PagerAdapter extends FragmentStateAdapter {
    private static final int NUM_PAGES = 2;

    public PagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return new ThirdPageFragment();
            case 1:
                return new SecondPageFragment();
            default:
                return new ThirdPageFragment();
        }
    }

    @Override
    public int getItemCount() {
        return NUM_PAGES;
    }
}