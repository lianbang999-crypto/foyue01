/**
 * Lifebear トップページ スクリプト
 * - ページロード時のアニメーション
 * - カルーセル自動再生（CSSベースの切り替え）
 * - スクロールアニメーション
 */

(function() {
    'use strict';

    // ==========================================
    // カルーセル（CSSベース + 自動再生）
    // ==========================================
    class Carousel {
        constructor(element) {
            this.container = element;
            this.radios = Array.from(element.querySelectorAll('.carousel__radio'));
            this.slideCount = this.radios.length;
            this.autoPlayInterval = null;
            this.autoPlayDelay = 20000; // 20秒

            this.init();
        }

        init() {
            // ラジオボタンの変更時に自動再生をリセット
            this.radios.forEach(radio => {
                radio.addEventListener('change', () => {
                    this.resetAutoPlay();
                });
            });

            // 自動再生開始
            this.startAutoPlay();
        }

        getCurrentIndex() {
            return this.radios.findIndex(radio => radio.checked);
        }

        goToSlide(index) {
            // インデックスを正規化（ループ）
            const normalizedIndex = ((index % this.slideCount) + this.slideCount) % this.slideCount;
            this.radios[normalizedIndex].checked = true;
        }

        nextSlide() {
            this.goToSlide(this.getCurrentIndex() + 1);
        }

        startAutoPlay() {
            this.autoPlayInterval = setInterval(() => {
                this.nextSlide();
            }, this.autoPlayDelay);
        }

        stopAutoPlay() {
            if (this.autoPlayInterval) {
                clearInterval(this.autoPlayInterval);
                this.autoPlayInterval = null;
            }
        }

        resetAutoPlay() {
            this.stopAutoPlay();
            this.startAutoPlay();
        }
    }

    // ==========================================
    // スクロールアニメーション（IntersectionObserver）
    // ==========================================
    function setupScrollAnimations() {
        const personalizationSection = document.querySelector('#personalization');
        if (!personalizationSection) return;

        let observed = false;
        const observer = new IntersectionObserver(
            (entries) => {
                if (!entries[0].isIntersecting) return;
                if (observed) return;

                // personalization セクション内の will-animate 要素にアニメーションを適用
                const animateElements = personalizationSection.querySelectorAll('.will-animate');
                animateElements.forEach((el) => {
                    el.classList.add('animate');
                });

                observed = true;
            },
            {
                rootMargin: '0px',
                threshold: 0.3 // セクションが30%表示されたらアニメーション開始
            }
        );

        observer.observe(personalizationSection);
    }

    // ==========================================
    // ページロードアニメーション
    // ==========================================
    function setupLoadAnimations() {
        // top セクションと start セクションの will-animate 要素にアニメーションを適用
        const topSection = document.querySelector('#top');
        const startSection = document.querySelector('#start');

        if (topSection) {
            topSection.querySelectorAll('.will-animate').forEach((el) => {
                el.classList.add('animate');
            });
        }

        if (startSection) {
            startSection.querySelectorAll('.will-animate').forEach((el) => {
                el.classList.add('animate');
            });
        }
    }

    // ==========================================
    // 初期化
    // ==========================================
    function init() {
        // カルーセル初期化
        const carouselElement = document.querySelector('.carousel');
        if (carouselElement) {
            new Carousel(carouselElement);
        }

        // ページロードアニメーション
        setupLoadAnimations();

        // スクロールアニメーション
        setupScrollAnimations();
    }

    // DOMContentLoaded で初期化
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
